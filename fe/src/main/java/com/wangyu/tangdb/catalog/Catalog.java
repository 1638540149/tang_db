package com.wangyu.tangdb.catalog;

import com.wangyu.tangdb.execute.SqlError;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.IndexCodec;
import com.wangyu.tangdb.storage.IndexMeta;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 进程内元数据缓存,元数据本身存放在 BE 的系统表里(规约 FR-110 / D11)。
 *
 * <p>P3.5 起成为**库感知**的权威缓存:表名唯一性 = 库内唯一,表 ID 仍全局唯一;
 * 库元数据持久化到 {@code sys_databases}(ID 3),表归属持久化到 {@code sys_tables.db_name}。</p>
 *
 * <p>启动流程:{@link #open} → 幂等创建系统表(空库)→ 全量加载并校验(FR-117 / FR-424 宽容读)。
 * 任何"表缺列 / 没有主键 / next_table_id 非法"都会中止启动并指名对象,绝不自动修补。</p>
 *
 * <p>进程内单例 + 读写锁(K27 / FR-402);DDL 立即写系统表(FR-112),成功后对所有会话立即可见。</p>
 */
public final class Catalog {

    private final KvStore kv;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final Map<Long, TableMeta> byId = new LinkedHashMap<>();
    /** 库名(小写)→ 表名(小写)→ 表定义。 */
    private final Map<String, Map<String, TableMeta>> activeByDb = new LinkedHashMap<>();
    /** 全部库名(小写,插入序),始终含 default。 */
    private final Set<String> databases = new LinkedHashSet<>();
    /** 表 ID → 索引(含 DROPPED,按 index_id 升序)。权威副本在 sys_indexes(FR-482)。 */
    private final Map<Long, List<IndexMeta>> indexesByTable = new LinkedHashMap<>();
    /** 表 ID → 下一个可用索引 ID(每表从 1 起,只增不复用,FR-464 / K75)。 */
    private final Map<Long, Integer> nextIndexIds = new LinkedHashMap<>();
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

    /** 当前库(或指定库)的活跃用户表(按表名排序)。系统表不对外展示。 */
    public List<TableMeta> tables() {
        return tables(SystemTables.DEFAULT_DATABASE);
    }

    /** 指定库的活跃用户表(按表名排序)。 */
    public List<TableMeta> tables(String database) {
        lock.readLock().lock();
        try {
            Map<String, TableMeta> inDb = activeByDb.get(normalizeDbKey(database));
            if (inDb == null) {
                return List.of();
            }
            List<TableMeta> list = new ArrayList<>(inDb.values());
            list.sort(Comparator.comparing(TableMeta::name));
            return list;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 按名字查活跃表;不存在返回 null。 */
    public TableMeta find(String tableName) {
        return find(SystemTables.DEFAULT_DATABASE, tableName);
    }

    /** 按库 + 名字查活跃表;不存在返回 null。 */
    public TableMeta find(String database, String tableName) {
        if (tableName == null) {
            return null;
        }
        lock.readLock().lock();
        try {
            Map<String, TableMeta> inDb = activeByDb.get(normalizeDbKey(database));
            return inDb == null ? null : inDb.get(tableName.toLowerCase());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 按名字取表(默认库);不存在报"表 X 不存在"。 */
    public TableMeta require(String tableName) {
        return require(SystemTables.DEFAULT_DATABASE, tableName);
    }

    /** 按库 + 名字取表;不存在报"表 X 不存在"(1146)。 */
    public TableMeta require(String database, String tableName) {
        TableMeta t = find(database, tableName);
        if (t == null) {
            throw new CatalogException("表 " + tableName + " 不存在", SqlError.ER_NO_SUCH_TABLE);
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

    // ------------------------------------------------------------ 索引查询(SPEC-P4)

    /** 某表的全部"元数据可见"索引(不含 DROPPED),按索引 ID 升序。 */
    public List<IndexMeta> indexes(long tableId) {
        lock.readLock().lock();
        try {
            List<IndexMeta> all = indexesByTable.get(tableId);
            if (all == null) {
                return List.of();
            }
            List<IndexMeta> visible = new ArrayList<>(all.size());
            for (IndexMeta index : all) {
                if (!index.dropped()) {
                    visible.add(index);
                }
            }
            return visible;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 某表全部索引(含 DROPPED),供诊断/测试。 */
    public List<IndexMeta> allIndexes(long tableId) {
        lock.readLock().lock();
        try {
            List<IndexMeta> all = indexesByTable.get(tableId);
            return all == null ? List.of() : List.copyOf(all);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 查询可用的索引(只有 ACTIVE,§5.6 / FR-509)。 */
    public List<IndexMeta> activeIndexes(long tableId) {
        List<IndexMeta> out = new ArrayList<>();
        for (IndexMeta index : indexes(tableId)) {
            if (index.queryable()) {
                out.add(index);
            }
        }
        return out;
    }

    /** 按表 + 索引 ID 查(含 DROPPED);不存在返回 null。 */
    public IndexMeta findIndex(long tableId, int indexId) {
        lock.readLock().lock();
        try {
            List<IndexMeta> all = indexesByTable.get(tableId);
            if (all == null) {
                return null;
            }
            for (IndexMeta index : all) {
                if (index.indexId() == indexId) {
                    return index;
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 按表 + 索引名查元数据可见的索引(不含 DROPPED);不存在返回 null。 */
    public IndexMeta findIndexByName(long tableId, String name) {
        if (name == null) {
            return null;
        }
        String want = name.trim().toLowerCase();
        lock.readLock().lock();
        try {
            List<IndexMeta> all = indexesByTable.get(tableId);
            if (all == null) {
                return null;
            }
            for (IndexMeta index : all) {
                if (!index.dropped() && index.name().equals(want)) {
                    return index;
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 按库 + 表 + 索引名查。 */
    public IndexMeta findIndexByName(String database, String tableName, String name) {
        TableMeta table = find(database, tableName);
        return table == null ? null : findIndexByName(table.id(), name);
    }

    /** 某表上是否有元数据可见的索引建立在指定列上(FR-459)。 */
    public IndexMeta indexOnColumn(long tableId, String columnName) {
        String want = columnName.toLowerCase();
        for (IndexMeta index : indexes(tableId)) {
            if (index.columnName().equals(want)) {
                return index;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 库查询

    /** 全部库名(含 default,插入序)。 */
    public List<String> databases() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(databases);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 库是否存在(大小写不敏感,K69)。 */
    public boolean databaseExists(String name) {
        if (name == null) {
            return false;
        }
        lock.readLock().lock();
        try {
            return databases.contains(name.toLowerCase());
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 校验库存在;不存在报 1049(FR-418 / 边界 3)。 */
    public void requireDatabase(String name) {
        if (!databaseExists(name)) {
            throw new CatalogException("库 " + name + " 不存在", SqlError.ER_BAD_DB_ERROR);
        }
    }

    // ------------------------------------------------------------ 库 DDL

    /** 建库(FR-416):重名报 1007;名字规则与表名一致(含反引号);统一小写。 */
    public void createDatabase(String rawName) {
        lock.writeLock().lock();
        try {
            String name = normalizeDatabaseName(rawName);
            if (databases.contains(name)) {
                throw new CatalogException("库 " + name + " 已存在", SqlError.ER_DB_CREATE_EXISTS);
            }
            kv.put(SystemTables.databaseKey(name), SystemTables.encodeDatabaseRow(name, now(), null));
            databases.add(name);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 删库(FR-417):default 不可删;非空拒绝(1451);不存在报 1008。 */
    public void dropDatabase(String rawName) {
        lock.writeLock().lock();
        try {
            String name = normalizeDbKey(rawName);
            if (!databases.contains(name)) {
                throw new CatalogException("库 " + name + " 不存在", SqlError.ER_DB_DROP_EXISTS);
            }
            if (SystemTables.DEFAULT_DATABASE.equals(name)) {
                throw new CatalogException("default 库不可删除", SqlError.ER_ROW_IS_REFERENCED_2);
            }
            Map<String, TableMeta> inDb = activeByDb.get(name);
            if (inDb != null && !inDb.isEmpty()) {
                throw new CatalogException("库 " + name + " 非空,请先删表", SqlError.ER_ROW_IS_REFERENCED_2);
            }
            kv.put(SystemTables.databaseKey(name), RowCodec.encodeTombstone(SystemTables.SYS_DATABASES));
            databases.remove(name);
            activeByDb.remove(name);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------ 表 DDL

    public TableMeta createTable(String tableName, List<Column> columns) {
        return createTable(SystemTables.DEFAULT_DATABASE, tableName, columns);
    }

    /**
     * 建表内联索引的声明(SPEC-P4 FR-462 / FR-463)。
     *
     * @param name    null / 空 = 自动命名 {@code <表>_<列>_idx} / {@code _uk}
     * @param column  被索引的列
     * @param unique  是否唯一索引
     */
    public record IndexSpec(String name, String column, boolean unique) {
    }

    /**
     * 在指定库建表。columns 的 ordinal 会被规范化为列表顺序。
     *
     * <p>P3 起:表记录 + 全部列记录 + sys_counters 更新进**同一条批记录**
     * (SPEC-P3 FR-355),所以不存在"表在、列缺"或"没有主键"的半套元数据。</p>
     *
     * <p>P4:内联索引与建表在**同一批记录**里(FR-462);表是空的,所以内联索引直接 ACTIVE。</p>
     */
    public TableMeta createTable(String database, String tableName, List<Column> columns) {
        return createTable(database, tableName, columns, List.of());
    }

    public TableMeta createTable(String database, String tableName, List<Column> columns,
                                 List<IndexSpec> inlineIndexes) {
        lock.writeLock().lock();
        try {
            String db = requireDbForWrite(database);
            String name = normalizeTableName(tableName);
            Map<String, TableMeta> inDb = activeByDb.computeIfAbsent(db, k -> new LinkedHashMap<>());
            if (inDb.containsKey(name)) {
                throw new CatalogException("表 " + name + " 已存在", SqlError.ER_TABLE_EXISTS_ERROR);
            }
            List<Column> normalized = validateColumns(columns);
            long id = nextTableId;
            TableMeta table = new TableMeta(id, name, db, TableMeta.STATE_ACTIVE, now(), normalized, false);

            List<KeyValue> batch = new ArrayList<>();
            stageCounter(batch, id + 1);
            stageTable(batch, table);

            // 内联索引:先规划好名字与 ID,再与表/列写进同一个批(FR-462)。
            List<IndexMeta> created = planInlineIndexes(table, inlineIndexes);
            int nextIndexId = 1;
            for (IndexMeta index : created) {
                nextIndexId = Math.max(nextIndexId, index.indexId() + 1);
                batch.add(new KeyValue(SystemTables.indexKey(table.id(), index.indexId()),
                        SystemTables.encodeIndexRow(index)));
            }
            if (!created.isEmpty()) {
                batch.add(new KeyValue(SystemTables.indexCounterKey(table.id()),
                        SystemTables.encodeCounterRow(SystemTables.indexCounterName(table.id()), nextIndexId)));
            }
            kv.writeBatch(batch);

            nextTableId = id + 1;
            byId.put(id, table);
            inDb.put(name, table);
            if (!created.isEmpty()) {
                indexesByTable.put(id, new ArrayList<>(created));
                nextIndexIds.put(id, nextIndexId);
            }
            return table;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 删表:只把状态标为 dropped,数据保留但不再参与任何查询(FR-124 / K18)。 */
    public void dropTable(String tableName) {
        dropTable(SystemTables.DEFAULT_DATABASE, tableName);
    }

    public void dropTable(String database, String tableName) {
        lock.writeLock().lock();
        try {
            String db = requireDbForWrite(database);
            String name = normalizeTableName(tableName);
            Map<String, TableMeta> inDb = activeByDb.get(db);
            TableMeta table = inDb == null ? null : inDb.get(name);
            if (table == null) {
                throw new CatalogException("表 " + name + " 不存在", SqlError.ER_NO_SUCH_TABLE);
            }
            TableMeta dropped = new TableMeta(table.id(), table.name(), table.dbName(),
                    TableMeta.STATE_DROPPED, table.createdAt(), table.columns(), false);
            List<KeyValue> batch = new ArrayList<>();
            batch.add(new KeyValue(SystemTables.tableKey(dropped.id()),
                    SystemTables.encodeTableRow(dropped.id(), dropped.name(), dropped.state(),
                            dropped.createdAt(), dropped.dbName())));
            kv.writeBatch(batch);

            byId.put(dropped.id(), dropped);
            inDb.remove(name);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public TableMeta truncateTable(String tableName) {
        return truncateTable(SystemTables.DEFAULT_DATABASE, tableName);
    }

    /**
     * 清空表:换一个新的表 ID(等价于一张新的空表),旧 ID 标记 dropped。
     * 旧数据不做物理删除,等后续期次 compaction 回收(K18)。
     *
     * <p>新表记录 + 列记录 + 旧表 dropped + 计数器进同一条批记录(FR-355)。</p>
     */
    public TableMeta truncateTable(String database, String tableName) {
        lock.writeLock().lock();
        try {
            String db = requireDbForWrite(database);
            String name = normalizeTableName(tableName);
            Map<String, TableMeta> inDb = activeByDb.get(db);
            TableMeta old = inDb == null ? null : inDb.get(name);
            if (old == null) {
                throw new CatalogException("表 " + name + " 不存在", SqlError.ER_NO_SUCH_TABLE);
            }
            long newId = nextTableId;
            TableMeta fresh = new TableMeta(newId, name, db, TableMeta.STATE_ACTIVE, now(), old.columns(), false);
            TableMeta dropped = new TableMeta(old.id(), old.name(), old.dbName(), TableMeta.STATE_DROPPED,
                    old.createdAt(), old.columns(), false);

            List<KeyValue> batch = new ArrayList<>();
            stageCounter(batch, newId + 1);
            stageTable(batch, fresh);
            batch.add(new KeyValue(SystemTables.tableKey(dropped.id()),
                    SystemTables.encodeTableRow(dropped.id(), dropped.name(), dropped.state(),
                            dropped.createdAt(), dropped.dbName())));
            kv.writeBatch(batch);

            nextTableId = newId + 1;
            byId.put(newId, fresh);
            byId.put(dropped.id(), dropped);
            inDb.put(name, fresh);
            return fresh;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------ 索引 DDL(SPEC-P4 FR-456 ~ FR-471)

    /**
     * 建索引(FR-456 ~ FR-460 / FR-464 / FR-465)。
     *
     * <p>非空表先以 {@code BUILDING} 落元数据,由调用方(Executor)分批回填后转 {@code ACTIVE};
     * 空表直接 {@code ACTIVE}。同名 BUILDING 索引且定义相同 → 返回它(供调用方续做,FR-469)。</p>
     *
     * @return 新建或待续做的索引
     */
    public IndexMeta createIndex(String database, String tableName, String rawName,
                                 String rawColumn, boolean unique) {
        lock.writeLock().lock();
        try {
            String db = requireDbForWrite(database);
            TableMeta table = requireTableInDb(db, tableName);
            if (rawColumn == null || rawColumn.isBlank()) {
                throw new CatalogException("建索引必须指定列");
            }
            Column column = table.column(rawColumn.trim());
            if (column == null) {
                throw new CatalogException("列 " + rawColumn + " 不存在(表 " + table.name() + ")",
                        SqlError.ER_BAD_FIELD_ERROR);
            }
            if (column.primaryKey()) {
                throw new CatalogException("主键列 " + column.name()
                        + " 已有主键索引,不能在其上再建索引(表 " + table.name() + ")");
            }
            if (!IndexCodec.indexable(column.type())) {
                throw new CatalogException("列 " + column.name() + " 的类型 " + column.typeName()
                        + " 不支持索引(只允许 INT / BIGINT / VARCHAR / DATE / BOOLEAN)");
            }

            String name = (rawName == null || rawName.isBlank())
                    ? autoIndexName(table.name(), column.name(),
                            unique ? "_uk" : "_idx", usedIndexNames(table.id()))
                    : normalizeIndexName(rawName);

            IndexMeta existing = findIndexByName(table.id(), name);
            if (existing != null) {
                if (existing.state() == IndexMeta.STATE_BUILDING
                        && existing.unique() == unique
                        && existing.columnName().equals(column.name())) {
                    return existing; // 回填中断后续做(FR-469 / 边界 7)
                }
                throw new CatalogException("索引 " + name + " 已存在(表 " + table.name() + ")",
                        SqlError.ER_DUP_KEYNAME);
            }
            IndexMeta sameColumn = indexOnColumn(table.id(), column.name());
            if (sameColumn != null) {
                throw new CatalogException("列 " + column.name() + " 上已有索引 "
                        + sameColumn.name() + ",不允许重复建索引(表 " + table.name() + ")");
            }

            int indexId = nextIndexIdFor(table.id());
            boolean hasRows = hasLiveRows(table);
            int state = hasRows ? IndexMeta.STATE_BUILDING : IndexMeta.STATE_ACTIVE;
            IndexMeta index = new IndexMeta(table.id(), indexId, name, column.name(),
                    unique, state, "", now());

            List<KeyValue> batch = new ArrayList<>();
            batch.add(new KeyValue(SystemTables.indexKey(table.id(), indexId),
                    SystemTables.encodeIndexRow(index)));
            batch.add(new KeyValue(SystemTables.indexCounterKey(table.id()),
                    SystemTables.encodeCounterRow(SystemTables.indexCounterName(table.id()), indexId + 1)));
            kv.writeBatch(batch);

            nextIndexIds.put(table.id(), indexId + 1);
            cacheIndex(index);
            return index;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 删索引(FR-461 / FR-480):只把状态标 DROPPED,数据留给 P5 的 compaction。 */
    public IndexMeta dropIndex(String database, String tableName, String rawName) {
        lock.writeLock().lock();
        try {
            String db = requireDbForWrite(database);
            TableMeta table = requireTableInDb(db, tableName);
            String name = normalizeIndexName(rawName);
            IndexMeta index = findIndexByName(table.id(), name);
            if (index == null) {
                throw new CatalogException("索引 " + name + " 不存在(表 " + table.name() + ")",
                        SqlError.ER_CANT_DROP_FIELD_OR_KEY);
            }
            return writeIndexState(index, IndexMeta.STATE_DROPPED);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 索引状态流转(FR-470 / K86:BUILDING → ACTIVE / DROPPED,不允许 ACTIVE → BUILDING)。 */
    public IndexMeta updateIndexState(IndexMeta index, int newState) {
        lock.writeLock().lock();
        try {
            IndexMeta current = findIndex(index.tableId(), index.indexId());
            if (current == null) {
                throw new CatalogException("索引 " + index.name() + "(ID " + index.indexId()
                        + ")不存在(表 ID " + index.tableId() + ")");
            }
            if (current.state() == IndexMeta.STATE_ACTIVE && newState == IndexMeta.STATE_BUILDING) {
                throw new CatalogException("索引 " + index.name()
                        + " 已是 ACTIVE,不允许退回 BUILDING(要重建请先 DROP,再 CREATE)");
            }
            return writeIndexState(current, newState);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 推进回填游标(FR-468 / K85):游标 = 最后一个已回填主键编码的十六进制。 */
    public IndexMeta updateBuildCursor(IndexMeta index, String cursorHex) {
        lock.writeLock().lock();
        try {
            IndexMeta current = findIndex(index.tableId(), index.indexId());
            if (current == null) {
                throw new CatalogException("索引 " + index.name() + " 不存在");
            }
            IndexMeta updated = current.withBuildCursor(cursorHex);
            kv.put(SystemTables.indexKey(updated.tableId(), updated.indexId()),
                    SystemTables.encodeIndexRow(updated));
            cacheIndex(updated);
            return updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 回填的一批:索引项写入与游标推进进**同一条批记录**(FR-467 / FR-468 / FR-477),
     * 崩溃后要么这一批全在、要么全不在,重跑时幂等补写。
     */
    public IndexMeta advanceBackfill(IndexMeta index, String cursorHex, List<KeyValue> writes) {
        lock.writeLock().lock();
        try {
            IndexMeta current = findIndex(index.tableId(), index.indexId());
            if (current == null) {
                throw new CatalogException("索引 " + index.name() + " 不存在");
            }
            IndexMeta updated = current.withBuildCursor(cursorHex);
            List<KeyValue> batch = new ArrayList<>(writes == null ? List.of() : writes);
            batch.add(new KeyValue(SystemTables.indexKey(updated.tableId(), updated.indexId()),
                    SystemTables.encodeIndexRow(updated)));
            kv.writeBatch(batch);
            cacheIndex(updated);
            return updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private IndexMeta writeIndexState(IndexMeta index, int state) {
        IndexMeta updated = index.withState(state);
        kv.put(SystemTables.indexKey(updated.tableId(), updated.indexId()),
                SystemTables.encodeIndexRow(updated));
        cacheIndex(updated);
        return updated;
    }

    private void cacheIndex(IndexMeta index) {
        List<IndexMeta> list = indexesByTable.computeIfAbsent(index.tableId(),
                k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).indexId() == index.indexId()) {
                list.set(i, index);
                return;
            }
        }
        list.add(index);
        list.sort(Comparator.comparingInt(IndexMeta::indexId));
    }

    private TableMeta requireTableInDb(String db, String tableName) {
        TableMeta table = find(db, tableName);
        if (table == null) {
            throw new CatalogException("表 " + tableName + " 不存在", SqlError.ER_NO_SUCH_TABLE);
        }
        return table;
    }

    /** 某表的下一个索引 ID(只增不复用):取计数器与已有 ID 的较大者(FR-464)。 */
    private int nextIndexIdFor(long tableId) {
        Integer cached = nextIndexIds.get(tableId);
        int fromMeta = 1;
        List<IndexMeta> existing = indexesByTable.get(tableId);
        if (existing != null) {
            for (IndexMeta index : existing) {
                fromMeta = Math.max(fromMeta, index.indexId() + 1);
            }
        }
        int value = Math.max(cached == null ? 1 : cached, fromMeta);
        int fromCounter = readIndexCounter(tableId);
        value = Math.max(value, fromCounter);
        return Math.max(value, 1);
    }

    private int readIndexCounter(long tableId) {
        byte[] raw = kv.get(SystemTables.indexCounterKey(tableId));
        if (raw == null) {
            return 1;
        }
        try {
            RowCodec.Decoded decoded = RowCodec.decode(SystemTables.COUNTERS, raw);
            if (decoded.tombstone()) {
                return 1;
            }
            return ((Number) decoded.values()[1]).intValue();
        } catch (StoreException e) {
            throw new CatalogException("元数据损坏:索引计数器(table_id=" + tableId
                    + ")无法解析(" + e.getMessage() + ")", e);
        }
    }

    /** 表里是否还有"活行"(墓碑不算),决定新索引是 BUILDING 还是 ACTIVE。 */
    private boolean hasLiveRows(TableMeta table) {
        List<KeyValue> rows = kv.scan(KeyCodec.tableRangeStart(table.id()),
                KeyCodec.tableRangeEnd(table.id()), 0);
        for (KeyValue row : rows) {
            if (KeyCodec.indexId(row.key()) != KeyCodec.INDEX_ID_PRIMARY) {
                continue; // 索引项不是"行"
            }
            if (row.value() == null || row.value().length == 0) {
                continue;
            }
            if (row.value()[0] == RowCodec.FORMAT_TOMBSTONE) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** 内联索引:校验 + 自动命名 + 分配 ID(与建表同批写入,FR-462)。 */
    private List<IndexMeta> planInlineIndexes(TableMeta table, List<IndexSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        Set<String> usedNames = new LinkedHashSet<>();
        Set<String> usedColumns = new LinkedHashSet<>();
        List<IndexMeta> plan = new ArrayList<>();
        int next = 1;
        for (IndexSpec spec : specs) {
            if (spec.column() == null || spec.column().isBlank()) {
                throw new CatalogException("内联索引必须指定列(表 " + table.name() + ")");
            }
            Column column = table.column(spec.column().trim());
            if (column == null) {
                throw new CatalogException("列 " + spec.column() + " 不存在(表 " + table.name() + ")",
                        SqlError.ER_BAD_FIELD_ERROR);
            }
            if (column.primaryKey()) {
                throw new CatalogException("主键列 " + column.name() + " 已有主键索引,不能在其上再建索引");
            }
            if (!IndexCodec.indexable(column.type())) {
                throw new CatalogException("列 " + column.name() + " 的类型 " + column.typeName()
                        + " 不支持索引");
            }
            if (!usedColumns.add(column.name())) {
                throw new CatalogException("列 " + column.name() + " 在建表语句里声明了多个索引");
            }
            String name = (spec.name() == null || spec.name().isBlank())
                    ? autoIndexName(table.name(), column.name(), spec.unique() ? "_uk" : "_idx", usedNames)
                    : normalizeIndexName(spec.name());
            if (!usedNames.add(name)) {
                throw new CatalogException("索引名 " + name + " 在建表语句里重复", SqlError.ER_DUP_KEYNAME);
            }
            plan.add(new IndexMeta(table.id(), next++, name, column.name(), spec.unique(),
                    IndexMeta.STATE_ACTIVE, "", now()));
        }
        return plan;
    }

    private Set<String> usedIndexNames(long tableId) {
        Set<String> names = new LinkedHashSet<>();
        List<IndexMeta> existing = indexesByTable.get(tableId);
        if (existing != null) {
            for (IndexMeta index : existing) {
                if (!index.dropped()) {
                    names.add(index.name());
                }
            }
        }
        return names;
    }

    /** 自动命名 `<表>_<列>_idx` / `_uk`,重名自动加序号(FR-463),并截断到 64 字节。 */
    private static String autoIndexName(String table, String column, String suffix, Set<String> used) {
        String base = truncateName(table + "_" + column + suffix);
        if (!used.contains(base)) {
            return base;
        }
        for (int i = 2; i < 10_000; i++) {
            String candidate = truncateName(base + "_" + i);
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        throw new CatalogException("无法为索引生成唯一名字(表 " + table + ",列 " + column + ")");
    }

    private static String truncateName(String name) {
        return name.length() <= 64 ? name : name.substring(0, 64);
    }

    // ------------------------------------------------------------ 启动:系统表与加载

    private void bootstrapSystemTables() {
        // 空库:写出系统表自身的定义 + next_table_id 初值 64 + default 库。
        // 逐条"不存在才写",所以半途崩溃后再次启动仍能补齐(系统表定义来自代码,不是用户数据)。
        ensureTable(SystemTables.TABLES);
        ensureTable(SystemTables.COLUMNS);
        ensureTable(SystemTables.DATABASES);
        ensureTable(SystemTables.COUNTERS);
        // P4 FR-474:老目录(没有 sys_indexes)在这里被**幂等补齐**,format_version 不变。
        ensureTable(SystemTables.INDEXES);
        if (kv.get(SystemTables.counterKey(SystemTables.COUNTER_NEXT_TABLE_ID)) == null) {
            kv.put(SystemTables.counterKey(SystemTables.COUNTER_NEXT_TABLE_ID),
                    SystemTables.encodeCounterRow(SystemTables.COUNTER_NEXT_TABLE_ID,
                            SystemTables.INITIAL_NEXT_TABLE_ID));
        }
        // default 库幂等创建(FR-427)。
        if (kv.get(SystemTables.databaseKey(SystemTables.DEFAULT_DATABASE)) == null) {
            kv.put(SystemTables.databaseKey(SystemTables.DEFAULT_DATABASE),
                    SystemTables.encodeDatabaseRow(SystemTables.DEFAULT_DATABASE, now(), "默认库"));
        }
    }

    private void ensureTable(TableMeta table) {
        if (kv.get(SystemTables.tableKey(table.id())) == null) {
            kv.put(SystemTables.tableKey(table.id()),
                    SystemTables.encodeTableRow(table.id(), table.name(), table.state(),
                            table.createdAt(), null));
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
            activeByDb.clear();
            databases.clear();
            indexesByTable.clear();
            nextIndexIds.clear();

            List<Object[]> tableRows = readTableRows();
            Map<Long, List<Column>> columns = readColumnRows();
            loadDatabases();

            for (Object[] row : tableRows) {
                long id = ((Number) row[0]).longValue();
                String name = (String) row[1];
                int state = ((Number) row[2]).intValue();
                String createdAt = row[3] == null ? "" : (String) row[3];
                String dbName = row[4] == null || ((String) row[4]).isBlank()
                        ? null : ((String) row[4]).toLowerCase();

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

                TableMeta table = new TableMeta(id, name, dbName, state, createdAt, cols,
                        SystemTables.isSystemTable(id));
                // FR-424:老目录(缺 db 列)的用户表一律归 default。
                if (!table.system() && table.dbName() == null) {
                    table = new TableMeta(id, name, SystemTables.DEFAULT_DATABASE, state, createdAt,
                            cols, false);
                }
                byId.put(id, table);
                if (!SystemTables.isSystemTable(id) && state == TableMeta.STATE_ACTIVE) {
                    String effectiveDb = table.dbName();
                    if (!databases.contains(effectiveDb)) {
                        throw new CatalogException("元数据非法:表 " + table.name() + "(ID " + id
                                + ") 所属库 " + effectiveDb + " 不存在");
                    }
                    Map<String, TableMeta> inDb = activeByDb.computeIfAbsent(effectiveDb,
                            k -> new LinkedHashMap<>());
                    if (inDb.containsKey(table.name())) {
                        throw new CatalogException("元数据非法:库 " + effectiveDb + " 内表名 "
                                + table.name() + " 重复(表 ID " + inDb.get(table.name()).id()
                                + " 与 " + id + ")");
                    }
                    inDb.put(table.name(), table);
                }
            }

            for (long sysId : SystemTables.REGISTERED_IDS) {
                if (!byId.containsKey(sysId)) {
                    throw new CatalogException("元数据不完整:缺少系统表(表 ID " + sysId + ")");
                }
            }

            loadCounter();
            loadIndexes();

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
                Object[] v = decodeTableRowTolerant(kvRow.value());
                rows.add(v);
            } catch (StoreException e) {
                throw new CatalogException("元数据损坏:sys_tables 中有一行无法解析(" + e.getMessage() + ")", e);
            }
        }
        return rows;
    }

    /**
     * 宽容解码 sys_tables 行(FR-424 / 边界 5):先按新 5 列形态解码,
     * 失败则回退到老 4 列形态(缺列/空值视为 default)。
     */
    private static Object[] decodeTableRowTolerant(byte[] value) {
        try {
            RowCodec.Decoded decoded = RowCodec.decode(SystemTables.TABLES, value);
            if (decoded.tombstone()) {
                throw new StoreException("墓碑记录不应出现在 sys_tables 的活跃扫描里");
            }
            Object[] v = decoded.values();
            return new Object[]{v[0], v[1], v[2], v[3], v[4]};
        } catch (StoreException e) {
            RowCodec.Decoded legacy = RowCodec.decode(SystemTables.TABLES_LEGACY, value);
            if (legacy.tombstone()) {
                throw new StoreException("墓碑记录不应出现在 sys_tables 的活跃扫描里");
            }
            Object[] v = legacy.values();
            return new Object[]{v[0], v[1], v[2], v[3], null};
        }
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

    private void loadDatabases() {
        List<KeyValue> kvs = kv.scan(KeyCodec.tableRangeStart(SystemTables.SYS_DATABASES),
                KeyCodec.tableRangeEnd(SystemTables.SYS_DATABASES), 0);
        for (KeyValue kvRow : kvs) {
            RowCodec.Decoded decoded;
            try {
                decoded = RowCodec.decode(SystemTables.DATABASES, kvRow.value());
            } catch (StoreException e) {
                throw new CatalogException("元数据损坏:sys_databases 中有一行无法解析(" + e.getMessage() + ")", e);
            }
            if (decoded.tombstone()) {
                continue;
            }
            Object[] v = decoded.values();
            databases.add(((String) v[0]).toLowerCase());
        }
        if (!databases.contains(SystemTables.DEFAULT_DATABASE)) {
            throw new CatalogException("元数据不完整:缺少 default 库");
        }
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
        if (value < SystemTables.MIN_TABLE_ID) {
            throw new CatalogException("元数据非法:sys_counters.next_table_id=" + value
                    + ",不应小于 " + SystemTables.MIN_TABLE_ID
                    + "(1~5 保留给系统表,不得分配)");
        }
        nextTableId = value;
    }

    /**
     * 从 sys_indexes 重建索引缓存(FR-475 / FR-476):索引指向不存在的表/列、同一表内
     * 索引 ID 重复、状态值非法,都中止启动并指名对象。
     */
    private void loadIndexes() {
        List<KeyValue> kvs = kv.scan(KeyCodec.tableRangeStart(SystemTables.SYS_INDEXES),
                KeyCodec.tableRangeEnd(SystemTables.SYS_INDEXES), 0);
        for (KeyValue kvRow : kvs) {
            Object[] v;
            try {
                RowCodec.Decoded decoded = RowCodec.decode(SystemTables.INDEXES, kvRow.value());
                if (decoded.tombstone()) {
                    continue;
                }
                v = decoded.values();
            } catch (StoreException e) {
                throw new CatalogException("元数据损坏:sys_indexes 中有一行无法解析("
                        + e.getMessage() + ")", e);
            }
            long tableId = ((Number) v[0]).longValue();
            int indexId = ((Number) v[1]).intValue();
            String name = (String) v[2];
            String columnName = (String) v[3];
            boolean unique = Boolean.TRUE.equals(v[4]);
            int state = ((Number) v[5]).intValue();
            String cursor = v[6] == null ? "" : (String) v[6];
            String createdAt = v[7] == null ? "" : (String) v[7];

            if (kvRow.key().length < 16) {
                throw new CatalogException("元数据损坏:sys_indexes 的行 key 长度非法("
                        + kvRow.key().length + "),应至少为 16 字节(8 字节表前缀 + table_id + index_id)");
            }
            long keyTableId = KeyCodec.decodeBe4(kvRow.key(), 8);
            int keyIndexId = (int) KeyCodec.decodeBe4(kvRow.key(), 12);
            if (keyTableId != tableId || keyIndexId != indexId) {
                throw new CatalogException("元数据非法:sys_indexes 的 key(table_id=" + keyTableId
                        + ",index_id=" + keyIndexId + ")与行内容(table_id=" + tableId
                        + ",index_id=" + indexId + ")不一致");
            }
            TableMeta table = byId.get(tableId);
            if (table == null) {
                throw new CatalogException("元数据非法:索引 " + name + "(表 ID " + tableId
                        + ",索引 ID " + indexId + ")指向不存在的表");
            }
            if (indexId <= 0) {
                throw new CatalogException("元数据非法:索引 " + name + "(表 " + table.name()
                        + ")的索引 ID " + indexId + " 必须 ≥1(0 是主键)");
            }
            if (name == null || name.isBlank()) {
                throw new CatalogException("元数据非法:表 " + table.name() + "(ID " + tableId
                        + ")有索引缺少索引名");
            }
            if (columnName == null || columnName.isBlank()) {
                throw new CatalogException("元数据非法:索引 " + name + "(表 " + table.name()
                        + ")缺少索引列");
            }
            Column column = table.column(columnName);
            if (column == null) {
                throw new CatalogException("元数据非法:索引 " + name + "(表 " + table.name()
                        + ")指向不存在的列 " + columnName);
            }
            if (!IndexCodec.indexable(column.type())) {
                throw new CatalogException("元数据非法:索引 " + name + "(表 " + table.name()
                        + ")的列 " + column.name() + " 类型 " + column.typeName() + " 不支持索引");
            }
            if (state != IndexMeta.STATE_BUILDING && state != IndexMeta.STATE_ACTIVE
                    && state != IndexMeta.STATE_DROPPED) {
                throw new CatalogException("元数据非法:索引 " + name + "(表 " + table.name()
                        + ")的状态值 " + state + " 无法识别");
            }

            IndexMeta index = new IndexMeta(tableId, indexId, name, column.name(), unique,
                    state, cursor, createdAt);
            List<IndexMeta> list = indexesByTable.computeIfAbsent(tableId, k -> new ArrayList<>());
            for (IndexMeta existing : list) {
                if (existing.indexId() == indexId) {
                    throw new CatalogException("元数据非法:表 " + table.name() + " 内索引 ID "
                            + indexId + " 重复(" + existing.name() + " 与 " + name + ")");
                }
                if (!existing.dropped() && !index.dropped() && existing.name().equals(index.name())) {
                    throw new CatalogException("元数据非法:表 " + table.name() + " 内索引名 "
                            + index.name() + " 重复");
                }
            }
            list.add(index);
            list.sort(Comparator.comparingInt(IndexMeta::indexId));
            nextIndexIds.merge(tableId, indexId + 1, Math::max);
        }
    }

    // ------------------------------------------------------------ 内部

    /**
     * 把"计数器推进 + 表记录 + 全部列记录"放进同一个批(FR-355)。
     * 计数器与表记录一起原子生效:即使随后崩溃,也只会浪费一个 ID,不会复用(FR-113)。
     */
    private static void stageTable(List<KeyValue> batch, TableMeta table) {
        batch.add(new KeyValue(SystemTables.tableKey(table.id()),
                SystemTables.encodeTableRow(table.id(), table.name(), table.state(),
                        table.createdAt(), table.dbName())));
        for (Column column : table.columns()) {
            batch.add(new KeyValue(SystemTables.columnKey(table.id(), column.ordinal()),
                    SystemTables.encodeColumnRow(column, table.id())));
        }
    }

    private static void stageCounter(List<KeyValue> batch, long nextId) {
        batch.add(new KeyValue(SystemTables.counterKey(SystemTables.COUNTER_NEXT_TABLE_ID),
                SystemTables.encodeCounterRow(SystemTables.COUNTER_NEXT_TABLE_ID, nextId)));
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

    /** 索引名规则与表名一致(FR-460):≤64、大小写不敏感、只允许字母数字下划线。 */
    private static String normalizeIndexName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new CatalogException("索引名不能为空");
        }
        String name = rawName.trim().toLowerCase();
        if (name.length() > 64) {
            throw new CatalogException("索引名超长(限 64 字节): " + rawName);
        }
        if (!name.matches("[a-z_][a-z0-9_]*")) {
            throw new CatalogException("索引名非法: " + rawName + "(规则与表名一致)");
        }
        return name;
    }

    /** 库名:统一小写、限长 64、规则与表名一致、保留名拒绝(FR-416 / K69 / 边界 1/2)。 */
    private static String normalizeDatabaseName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new CatalogException("库名不能为空");
        }
        String name = rawName.trim().toLowerCase();
        if (name.length() > 64) {
            throw new CatalogException("库名超长(限 64 字节): " + rawName);
        }
        if (!name.matches("[a-z_][a-z0-9_]*")) {
            throw new CatalogException("库名非法: " + rawName + "(规则与表名一致,只允许字母、数字、下划线)");
        }
        if (SystemTables.DEFAULT_DATABASE.equals(name) || name.startsWith("sys_")) {
            throw new CatalogException("库名 " + rawName + " 是保留名,不可使用");
        }
        return name;
    }

    /** 校验用于建表/删表/查表的库名存在(1049),返回小写库名。 */
    private String requireDbForWrite(String database) {
        String db = normalizeDbKey(database);
        if (!databases.contains(db)) {
            throw new CatalogException("库 " + db + " 不存在", SqlError.ER_BAD_DB_ERROR);
        }
        return db;
    }

    private static String normalizeDbKey(String database) {
        return (database == null || database.isBlank())
                ? SystemTables.DEFAULT_DATABASE : database.trim().toLowerCase();
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
