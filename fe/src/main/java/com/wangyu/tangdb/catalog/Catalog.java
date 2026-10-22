package com.wangyu.tangdb.catalog;

import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.KeyValue;
import com.wangyu.tangdb.storage.KvStore;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.StoreException;
import com.wangyu.tangdb.storage.TableMeta;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 进程内元数据缓存,元数据本身存放在 BE 的系统表里(规约 FR-110 / D11)。
 *
 * <p>启动流程:{@link #open} → 幂等创建系统表(空库)→ 全量加载并校验(FR-117)。
 * 任何"表缺列 / 没有主键 / next_table_id 非法"都会中止启动并指名对象,绝不自动修补。</p>
 *
 * <p>进程内单例 + 读写锁(K27);DDL 立即写系统表(FR-112),成功后对所有会话立即可见。</p>
 */
public final class Catalog {

    private final KvStore kv;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final Map<Long, TableMeta> byId = new LinkedHashMap<>();
    private final Map<String, TableMeta> activeByName = new LinkedHashMap<>();
    private long nextTableId;

    private Catalog(KvStore kv) {
        this.kv = kv;
    }

    /** 打开 catalog:空库时幂等创建系统表,然后加载全部元数据。 */
    public static Catalog open(KvStore kv) {
        Catalog catalog = new Catalog(kv);
        catalog.bootstrapSystemTables();
        catalog.loadAll();
        return catalog;
    }

    // ------------------------------------------------------------ 查询

    /** 活跃的用户表(按表名排序)。系统表不对外展示。 */
    public List<TableMeta> tables() {
        lock.readLock().lock();
        try {
            List<TableMeta> list = new ArrayList<>(activeByName.values());
            list.sort(Comparator.comparing(TableMeta::name));
            return list;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 按名字查活跃表;不存在返回 null。 */
    public TableMeta find(String tableName) {
        if (tableName == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            return activeByName.get(tableName.toLowerCase());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 按名字取表;不存在报"表 X 不存在"。 */
    public TableMeta require(String tableName) {
        TableMeta t = find(tableName);
        if (t == null) {
            throw new CatalogException("表 " + tableName + " 不存在");
        }
        return t;
    }

    /** 按 ID 查表(含已 dropped 的)。 */
    public TableMeta findById(long id) {
        lock.readLock().lock();
        try {
            return byId.get(id);
        } finally {
            lock.readLock().unlock();
        }
    }

    public long nextTableId() {
        lock.readLock().lock();
        try {
            return nextTableId;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ------------------------------------------------------------ DDL

    /** 建表。columns 的 ordinal 会被规范化为列表顺序。 */
    public TableMeta createTable(String tableName, List<Column> columns) {
        lock.writeLock().lock();
        try {
            String name = normalizeTableName(tableName);
            if (activeByName.containsKey(name)) {
                throw new CatalogException("表 " + name + " 已存在");
            }
            List<Column> normalized = validateColumns(columns);
            long id = allocateTableId();
            TableMeta table = new TableMeta(id, name, TableMeta.STATE_ACTIVE, now(), normalized, false);
            persistTable(table);
            byId.put(id, table);
            activeByName.put(name, table);
            return table;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 删表:只把状态标为 dropped,数据保留但不再参与任何查询(FR-124 / K18)。 */
    public void dropTable(String tableName) {
        lock.writeLock().lock();
        try {
            String name = normalizeTableName(tableName);
            TableMeta table = activeByName.get(name);
            if (table == null) {
                throw new CatalogException("表 " + name + " 不存在");
            }
            TableMeta dropped = new TableMeta(table.id(), table.name(), TableMeta.STATE_DROPPED,
                    table.createdAt(), table.columns(), false);
            kv.put(SystemTables.tableKey(dropped.id()),
                    SystemTables.encodeTableRow(dropped.id(), dropped.name(), dropped.state(), dropped.createdAt()));
            byId.put(dropped.id(), dropped);
            activeByName.remove(name);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 清空表:换一个新的表 ID(等价于一张新的空表),旧 ID 标记 dropped。
     * 旧数据不做物理删除,等后续期次 compaction 回收(K18)。
     */
    public TableMeta truncateTable(String tableName) {
        lock.writeLock().lock();
        try {
            String name = normalizeTableName(tableName);
            TableMeta old = activeByName.get(name);
            if (old == null) {
                throw new CatalogException("表 " + name + " 不存在");
            }
            long newId = allocateTableId();
            TableMeta fresh = new TableMeta(newId, name, TableMeta.STATE_ACTIVE, now(), old.columns(), false);
            persistTable(fresh);
            TableMeta dropped = new TableMeta(old.id(), old.name(), TableMeta.STATE_DROPPED,
                    old.createdAt(), old.columns(), false);
            kv.put(SystemTables.tableKey(dropped.id()),
                    SystemTables.encodeTableRow(dropped.id(), dropped.name(), dropped.state(), dropped.createdAt()));

            byId.put(newId, fresh);
            byId.put(dropped.id(), dropped);
            activeByName.put(name, fresh);
            return fresh;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------ 启动:系统表与加载

    private void bootstrapSystemTables() {
        // 空库:写出三张系统表自身的定义 + next_table_id 初值 16。
        // 逐条"不存在才写",所以半途崩溃后再次启动仍能补齐(系统表定义来自代码,不是用户数据)。
        ensureTable(SystemTables.TABLES);
        ensureTable(SystemTables.COLUMNS);
        ensureTable(SystemTables.COUNTERS);
        if (kv.get(SystemTables.counterKey(SystemTables.COUNTER_NEXT_TABLE_ID)) == null) {
            kv.put(SystemTables.counterKey(SystemTables.COUNTER_NEXT_TABLE_ID),
                    SystemTables.encodeCounterRow(SystemTables.COUNTER_NEXT_TABLE_ID,
                            SystemTables.INITIAL_NEXT_TABLE_ID));
        }
    }

    private void ensureTable(TableMeta table) {
        if (kv.get(SystemTables.tableKey(table.id())) == null) {
            kv.put(SystemTables.tableKey(table.id()),
                    SystemTables.encodeTableRow(table.id(), table.name(), table.state(), table.createdAt()));
        }
        for (Column column : table.columns()) {
            if (kv.get(SystemTables.columnKey(table.id(), column.ordinal())) == null) {
                kv.put(SystemTables.columnKey(table.id(), column.ordinal()),
                        SystemTables.encodeColumnRow(column, table.id()));
            }
        }
    }

    private void loadAll() {
        lock.writeLock().lock();
        try {
            byId.clear();
            activeByName.clear();

            List<Object[]> tableRows = readTableRows();
            Map<Long, List<Column>> columns = readColumnRows();

            for (Object[] row : tableRows) {
                long id = ((Number) row[0]).longValue();
                String name = (String) row[1];
                int state = ((Number) row[2]).intValue();
                String createdAt = row[3] == null ? "" : (String) row[3];

                if (id <= 0) {
                    throw new CatalogException("元数据非法:sys_tables 里出现非法表 ID " + id);
                }
                if (name == null || name.isBlank()) {
                    throw new CatalogException("元数据非法:表 ID " + id + " 缺少表名");
                }
                if (state != TableMeta.STATE_ACTIVE && state != TableMeta.STATE_DROPPED) {
                    throw new CatalogException("元数据非法:表 " + name + "(ID " + id + ")的状态值 "
                            + state + " 无法识别");
                }
                List<Column> cols = columns.get(id);
                if (cols == null || cols.isEmpty()) {
                    throw new CatalogException("元数据不完整:表 " + name + "(ID " + id + ") 缺少列定义");
                }
                cols.sort(Comparator.comparingInt(Column::ordinal));
                for (int i = 0; i < cols.size(); i++) {
                    if (cols.get(i).ordinal() != i) {
                        throw new CatalogException("元数据非法:表 " + name + "(ID " + id
                                + ") 的列序号不连续(第 " + (i + 1) + " 列序号为 " + cols.get(i).ordinal() + ")");
                    }
                }
                long pkCount = cols.stream().filter(Column::primaryKey).count();
                if (pkCount == 0) {
                    throw new CatalogException("元数据不完整:表 " + name + "(ID " + id + ") 没有主键");
                }
                if (pkCount > 1) {
                    throw new CatalogException("元数据非法:表 " + name + "(ID " + id + ") 有多个主键");
                }
                Column pk = cols.stream().filter(Column::primaryKey).findFirst().orElseThrow();
                if (!pk.type().canBePrimaryKey()) {
                    throw new CatalogException("元数据非法:表 " + name + "(ID " + id + ") 的主键列 "
                            + pk.name() + " 类型为 " + pk.typeName() + ",不允许作为主键");
                }

                TableMeta table = new TableMeta(id, name, state, createdAt, cols,
                        SystemTables.isSystemTable(id));
                byId.put(id, table);
                if (!SystemTables.isSystemTable(id) && state == TableMeta.STATE_ACTIVE) {
                    if (activeByName.containsKey(table.name())) {
                        throw new CatalogException("元数据非法:表名 " + table.name() + " 重复(表 ID "
                                + activeByName.get(table.name()).id() + " 与 " + id + ")");
                    }
                    activeByName.put(table.name(), table);
                }
            }

            for (long sysId : new long[]{SystemTables.SYS_TABLES, SystemTables.SYS_COLUMNS, SystemTables.SYS_COUNTERS}) {
                if (!byId.containsKey(sysId)) {
                    throw new CatalogException("元数据不完整:缺少系统表(表 ID " + sysId + ")");
                }
            }

            loadCounter();

            long maxUserId = byId.keySet().stream()
                    .filter(id -> !SystemTables.isSystemTable(id))
                    .max(Long::compareTo).orElse(0L);
            if (nextTableId <= maxUserId) {
                throw new CatalogException("元数据非法:sys_counters.next_table_id=" + nextTableId
                        + " 不大于已分配的最大表 ID " + maxUserId + "(继续使用会导致表 ID 复用)");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private List<Object[]> readTableRows() {
        List<Object[]> rows = new ArrayList<>();
        List<KeyValue> kvs = kv.scan(KeyCodec.tableRangeStart(SystemTables.SYS_TABLES),
                KeyCodec.tableRangeEnd(SystemTables.SYS_TABLES), 0);
        for (KeyValue kvRow : kvs) {
            try {
                RowCodec.Decoded decoded = RowCodec.decode(SystemTables.TABLES, kvRow.value());
                if (decoded.tombstone()) {
                    continue;
                }
                Object[] v = decoded.values();
                rows.add(new Object[]{v[0], v[1], v[2], v[3]});
            } catch (StoreException e) {
                throw new CatalogException("元数据损坏:sys_tables 中有一行无法解析(" + e.getMessage() + ")", e);
            }
        }
        return rows;
    }

    private Map<Long, List<Column>> readColumnRows() {
        Map<Long, List<Column>> result = new LinkedHashMap<>();
        List<KeyValue> kvs = kv.scan(KeyCodec.tableRangeStart(SystemTables.SYS_COLUMNS),
                KeyCodec.tableRangeEnd(SystemTables.SYS_COLUMNS), 0);
        for (KeyValue kvRow : kvs) {
            Object[] v;
            try {
                RowCodec.Decoded decoded = RowCodec.decode(SystemTables.COLUMNS, kvRow.value());
                if (decoded.tombstone()) {
                    continue;
                }
                v = decoded.values();
            } catch (StoreException e) {
                throw new CatalogException("元数据损坏:sys_columns 中有一行无法解析(" + e.getMessage() + ")", e);
            }
            long tableId = ((Number) v[0]).longValue();
            int ordinal = ((Number) v[1]).intValue();
            String name = (String) v[2];
            int typeCode = ((Number) v[3]).intValue();
            int typeLen = ((Number) v[4]).intValue();
            boolean nullable = Boolean.TRUE.equals(v[5]);
            boolean isPk = Boolean.TRUE.equals(v[6]);
            DataType type;
            try {
                type = DataType.fromCode(typeCode);
            } catch (StoreException e) {
                throw new CatalogException("元数据非法:表 ID " + tableId + " 的列 " + name
                        + " 类型枚举 " + typeCode + " 无法识别", e);
            }
            Column column = new Column(name, type, typeLen, nullable, isPk, ordinal);
            result.computeIfAbsent(tableId, k -> new ArrayList<>()).add(column);
        }
        return result;
    }

    private void loadCounter() {
        List<KeyValue> kvs = kv.scan(KeyCodec.tableRangeStart(SystemTables.SYS_COUNTERS),
                KeyCodec.tableRangeEnd(SystemTables.SYS_COUNTERS), 0);
        Long value = null;
        for (KeyValue kvRow : kvs) {
            RowCodec.Decoded decoded;
            try {
                decoded = RowCodec.decode(SystemTables.COUNTERS, kvRow.value());
            } catch (StoreException e) {
                throw new CatalogException("元数据损坏:sys_counters 中有一行无法解析(" + e.getMessage() + ")", e);
            }
            if (decoded.tombstone()) {
                continue;
            }
            Object[] v = decoded.values();
            if (SystemTables.COUNTER_NEXT_TABLE_ID.equals(v[0])) {
                value = ((Number) v[1]).longValue();
            }
        }
        if (value == null) {
            throw new CatalogException("元数据不完整:缺少 sys_counters.next_table_id(键 sys_counters/"
                    + SystemTables.COUNTER_NEXT_TABLE_ID + ")");
        }
        if (value < SystemTables.INITIAL_NEXT_TABLE_ID) {
            throw new CatalogException("元数据非法:sys_counters.next_table_id=" + value
                    + ",应不小于 " + SystemTables.INITIAL_NEXT_TABLE_ID
                    + "(用户表 ID 从 16 起,1~15 保留给系统表)");
        }
        nextTableId = value;
    }

    // ------------------------------------------------------------ 内部

    private long allocateTableId() {
        long id = nextTableId;
        // 先落盘计数器,再使用新 ID:即使随后崩溃,也只会浪费一个 ID,不会复用(FR-113)。
        kv.put(SystemTables.counterKey(SystemTables.COUNTER_NEXT_TABLE_ID),
                SystemTables.encodeCounterRow(SystemTables.COUNTER_NEXT_TABLE_ID, id + 1));
        nextTableId = id + 1;
        return id;
    }

    private void persistTable(TableMeta table) {
        kv.put(SystemTables.tableKey(table.id()),
                SystemTables.encodeTableRow(table.id(), table.name(), table.state(), table.createdAt()));
        for (Column column : table.columns()) {
            kv.put(SystemTables.columnKey(table.id(), column.ordinal()),
                    SystemTables.encodeColumnRow(column, table.id()));
        }
    }

    private static List<Column> validateColumns(List<Column> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new CatalogException("建表至少要有一个列");
        }
        Set<String> names = new HashSet<>();
        int pkCount = 0;
        List<Column> normalized = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            for (int j = 0; j < i; j++) {
                if (columns.get(j).name().equalsIgnoreCase(c.name())) {
                    throw new CatalogException("列名重复: " + c.name());
                }
            }
            names.add(c.name());
            if (c.type() == DataType.VARCHAR && (c.length() < 1 || c.length() > 65535)) {
                throw new CatalogException("列 " + c.name() + " 的 VARCHAR 长度必须在 1-65535 之间");
            }
            if (c.primaryKey()) {
                pkCount++;
                if (!c.type().canBePrimaryKey()) {
                    throw new CatalogException("主键类型不支持: " + c.typeName()
                            + "(只允许 INT / BIGINT / VARCHAR / DATE)");
                }
            }
            normalized.add(new Column(c.name(), c.type(), c.length(), !c.primaryKey(),
                    c.primaryKey(), i));
        }
        if (pkCount == 0) {
            throw new CatalogException("必须有一个主键(且只能是单列主键)");
        }
        if (pkCount > 1) {
            throw new CatalogException("只能有一个主键(当前有 " + pkCount + " 个)");
        }
        return normalized;
    }

    private static String normalizeTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new CatalogException("表名不能为空");
        }
        String name = tableName.trim().toLowerCase();
        if (!name.matches("[a-z_][a-z0-9_]*")) {
            throw new CatalogException("表名非法: " + tableName + "(只允许字母、数字、下划线,且不能以数字开头)");
        }
        return name;
    }

    private static String now() {
        return LocalDateTime.now().withNano(0).toString();
    }

    /** 供测试与诊断:当前所有已知表(含 dropped)。 */
    public Collection<TableMeta> allTables() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(byId.values());
        } finally {
            lock.readLock().unlock();
        }
    }
}
