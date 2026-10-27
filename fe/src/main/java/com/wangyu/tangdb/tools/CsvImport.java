package com.wangyu.tangdb.tools;

import com.wangyu.tangdb.catalog.Catalog;
import com.wangyu.tangdb.catalog.ImportState;
import com.wangyu.tangdb.client.FeConfig;
import com.wangyu.tangdb.client.TangDbClient;
import com.wangyu.tangdb.execute.Executor;
import com.wangyu.tangdb.parser.Ast;
import com.wangyu.tangdb.storage.Column;
import com.wangyu.tangdb.storage.DataType;
import com.wangyu.tangdb.storage.KeyCodec;
import com.wangyu.tangdb.storage.RowCodec;
import com.wangyu.tangdb.storage.StoreException;
import com.wangyu.tangdb.storage.TableMeta;
import com.wangyu.tangdb.storage.Values;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CSV 批量导入工具(SPEC-P5 H 组 / FR-605 ~ FR-608 / FR-614 / K110)。
 *
 * <p>独立命令 {@code scripts/tools/csv-import}(I26 拍板),不塞进常驻服务进程。
 * 写入走与普通 DML 完全相同的通道:Executor → 一条批记录 → WAL → fsync → memtable。</p>
 *
 * <p>进度落在 {@code sys_import_state}:每批提交后更新;崩溃后重跑同一命令从断点续,
 * 重复行按主键去重(K110),不会产生重复数据。</p>
 */
public final class CsvImport {

    private CsvImport() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** 返回进程退出码(0 = 成功)。 */
    public static int run(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("错误: " + e.getMessage());
            System.err.println(Options.usage());
            return 2;
        }
        if (options.help) {
            System.out.println(Options.usage());
            return 0;
        }
        try {
            return importCsv(options);
        } catch (ImportFailure e) {
            System.err.println("导入失败: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("导入失败(IO): " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            System.err.println("导入失败: " + e.getMessage());
            return 1;
        }
    }

    private static int importCsv(Options options) throws IOException {
        FeConfig config = FeConfig.load(options.config);
        String host = options.host != null ? options.host : config.beHost();
        int port = options.port > 0 ? options.port : config.bePort();
        int batch = options.batch > 0 ? options.batch : config.csvImportBatchRows();

        Path file = Path.of(options.file).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new ImportFailure("CSV 文件不存在: " + file);
        }
        long fileSize = Files.size(file);
        long fileMtime = Files.getLastModifiedTime(file).toMillis();

        try (TangDbClient client = new TangDbClient(host, port)) {
            Catalog catalog = Catalog.open(client);
            String database = options.database == null ? "default" : options.database.toLowerCase(Locale.ROOT);
            catalog.requireDatabase(database);
            TableMeta table = catalog.require(database, options.table);
            Executor executor = new Executor(client, catalog);
            executor.useDatabase(database);

            return doImport(client, catalog, executor, database, table, file, fileSize, fileMtime,
                    batch, options.restart);
        }
    }

    private static int doImport(TangDbClient kv, Catalog catalog, Executor executor,
                                String database, TableMeta table, Path file, long fileSize,
                                long fileMtime, int batchRows, boolean restart) throws IOException {
        String importId = ImportState.idFor(database, table.name(), file);
        ImportState.Task task = ImportState.find(kv, importId);
        long startOffset = 0;
        long lastRowNo = 0;
        long committedRows = 0;
        if (!restart && task != null && task.fileSize() == fileSize && task.fileMtime() == fileMtime) {
            if (ImportState.STATUS_DONE.equals(task.status())) {
                System.out.println("该文件已导入完成(task=" + importId + ",rows="
                        + task.committedRows() + "),无需重复导入");
                return 0;
            }
            startOffset = task.lastOffset();
            lastRowNo = task.lastRowNo();
            committedRows = task.committedRows();
            System.out.println("从断点续传: offset=" + startOffset + " 已提交行=" + committedRows);
        }

        Header header = readHeader(file, table);
        if (startOffset == 0) {
            startOffset = header.headerEndOffset;
        }
        long totalEstimate = estimateRows(file);
        ImportState.Task current = new ImportState.Task(importId, table.id(), table.name(),
                file.toString(), fileSize, fileMtime, startOffset, lastRowNo, committedRows,
                totalEstimate, ImportState.STATUS_RUNNING, null, ImportState.nowText());
        ImportState.save(kv, current);

        long processed = committedRows;
        long skipped = 0;
        long rowNo = lastRowNo;
        long lastEndOffset = startOffset;
        long batchStartRow = rowNo + 1;

        try (InputStream raw = new FileInputStream(file.toFile());
             CsvParser parser = new CsvParser(raw, startOffset)) {
            List<List<Object>> rows = new ArrayList<>(batchRows);
            List<Long> rowNumbers = new ArrayList<>(batchRows);
            List<Long> endOffsets = new ArrayList<>(batchRows);
            CsvParser.Record record;
            while ((record = parser.next()) != null) {
                rowNo++;
                if (record.fields().isEmpty()) {
                    continue;
                }
                if (record.fields().size() != header.columns.size()) {
                    throw failure(rowNo, header.columns.get(0), "?",
                            "列数不符(表头 " + header.columns.size() + " 列,本行 "
                                    + record.fields().size() + " 列)");
                }
                List<Object> row = new ArrayList<>(header.columns.size());
                for (int i = 0; i < header.columns.size(); i++) {
                    CsvParser.Field field = record.fields().get(i);
                    row.add(field.isNull() ? null : field.value());
                }
                rows.add(row);
                rowNumbers.add(rowNo);
                endOffsets.add(record.endOffset());
                if (rows.size() >= batchRows) {
                    BatchResult result = flushBatch(kv, executor, catalog, database, table, header,
                            rows, rowNumbers, endOffsets, importId, file, fileSize, fileMtime,
                            totalEstimate, processed);
                    processed = result.processed();
                    skipped += result.skipped();
                    lastEndOffset = result.lastEndOffset();
                    rows.clear();
                    rowNumbers.clear();
                    endOffsets.clear();
                    ImportState.save(kv, current = current.withProgress(lastEndOffset, rowNo, processed));
                    System.out.println("已提交 " + processed + "/"
                            + (totalEstimate > 0 ? totalEstimate : "?") + " 行");
                }
            }
            if (!rows.isEmpty()) {
                BatchResult result = flushBatch(kv, executor, catalog, database, table, header,
                        rows, rowNumbers, endOffsets, importId, file, fileSize, fileMtime,
                        totalEstimate, processed);
                processed = result.processed();
                skipped += result.skipped();
                lastEndOffset = result.lastEndOffset();
                ImportState.save(kv, current = current.withProgress(lastEndOffset, rowNo, processed));
            }
        } catch (ImportFailure e) {
            ImportState.save(kv, current.withStatus(ImportState.STATUS_FAILED, e.getMessage()));
            throw e;
        } catch (IOException e) {
            String message = "读取 CSV 失败: " + e.getMessage();
            ImportState.save(kv, current.withStatus(ImportState.STATUS_FAILED, message));
            throw new ImportFailure(message);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            ImportState.save(kv, current.withStatus(ImportState.STATUS_FAILED, message));
            throw e;
        }

        ImportState.save(kv, current.withStatus(ImportState.STATUS_DONE, null));
        System.out.println("导入完成: 表=" + table.name() + " 行数=" + processed
                + "(跳过已存在 " + skipped + ") 任务=" + importId);
        return 0;
    }

    private record BatchResult(long processed, long skipped, long lastEndOffset) {
    }

    private static BatchResult flushBatch(TangDbClient kv, Executor executor, Catalog catalog,
                                          String database, TableMeta table, Header header,
                                          List<List<Object>> rows, List<Long> rowNumbers,
                                          List<Long> endOffsets, String importId, Path file,
                                          long fileSize, long fileMtime, long totalEstimate,
                                          long processedBefore) {
        Column pk = table.primaryKey();
        List<List<Object>> toInsert = new ArrayList<>(rows);
        List<Long> insertedRowNumbers = new ArrayList<>(rowNumbers);
        long skipped = 0;
        long processed = processedBefore + rows.size();
        long lastEndOffset = endOffsets.isEmpty() ? 0 : endOffsets.get(endOffsets.size() - 1);
        int pkIndex = header.columns.indexOf(pk.name());
        // 先做一次主键类型校验(报错要带行号/列名/原值)。
        for (int i = 0; i < rows.size(); i++) {
            try {
                Object pkValue = Values.coerce(rows.get(i).get(pkIndex), pk);
                if (pkValue == null) {
                    throw failure(rowNumbers.get(i), pk.name(), null, "主键列不能为 NULL");
                }
            } catch (RuntimeException e) {
                if (e instanceof ImportFailure f) {
                    throw f;
                }
                throw failure(rowNumbers.get(i), pk.name(), rows.get(i).get(pkIndex), e.getMessage());
            }
        }
        if (toInsert.isEmpty()) {
            return new BatchResult(processed, skipped, lastEndOffset);
        }
        try {
            executor.importInsert(new Ast.Insert(database, table.name(), header.columnNames,
                    toInsert));
        } catch (StoreException e) {
            String message = e.getMessage() == null ? "类型转换失败" : e.getMessage();
            if (e.code() == com.wangyu.tangdb.execute.SqlError.ER_DUP_ENTRY
                    || message.contains("主键冲突") || message.contains("唯一索引")) {
                // 断点续传 / 并发导入时预检查可能漏判最新提交:逐行写入,重复行按 K110 跳过。
                int inserted = 0;
                for (int i = 0; i < toInsert.size(); i++) {
                    try {
                        executor.importInsert(new Ast.Insert(database, table.name(),
                                header.columnNames, List.of(toInsert.get(i))));
                        inserted++;
                    } catch (StoreException dup) {
                        if (dup.code() != com.wangyu.tangdb.execute.SqlError.ER_DUP_ENTRY
                                && !String.valueOf(dup.getMessage()).contains("主键冲突")
                                && !String.valueOf(dup.getMessage()).contains("唯一索引")) {
                            throw failure(insertedRowNumbers.get(i), "?", "?", dup.getMessage());
                        }
                        skipped++;
                    }
                }
                return new BatchResult(processed, skipped, lastEndOffset);
            }
            int index = batchIndex(message);
            long rowNo = index >= 0 && index < insertedRowNumbers.size()
                    ? insertedRowNumbers.get(index) : rowNumbers.get(0);
            String column = columnFromMessage(message);
            Object raw = "?";
            int columnIndex = column == null ? -1 : header.columns.indexOf(column);
            if (columnIndex >= 0 && index >= 0 && index < toInsert.size()
                    && columnIndex < toInsert.get(index).size()) {
                raw = toInsert.get(index).get(columnIndex);
            }
            throw failure(rowNo, column == null ? "?" : column, raw, "整批未写入:" + message);
        }
        return new BatchResult(processed, skipped, lastEndOffset);
    }

    /** 从错误信息 "列 xxx" 里取列名(Executor 的插入错误格式是"第 k 组(列 x): ...")。 */
    private static String columnFromMessage(String message) {
        int start = message.indexOf("列 ");
        if (start < 0) {
            return null;
        }
        int end = start + 2;
        while (end < message.length()) {
            char c = message.charAt(end);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '$')) {
                break;
            }
            end++;
        }
        return end > start + 2 ? message.substring(start + 2, end) : null;
    }

    /** 从 "第 k 组..." 里取组序号(0-based);取不到返回 -1。 */
    private static int batchIndex(String message) {
        if (message == null) {
            return -1;
        }
        int start = message.indexOf("第 ");
        if (start < 0) {
            return -1;
        }
        int end = message.indexOf(' ', start + 2);
        if (end < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(message.substring(start + 2, end)) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static ImportFailure failure(long rowNo, String column, Object value, String message) {
        String shown = value == null ? "NULL" : value.toString();
        return new ImportFailure("第 " + rowNo + " 行 列 " + column + " 原值 '" + shown + "': "
                + message);
    }

    // ------------------------------------------------------------ 表头

    private record Header(List<String> columns, List<String> columnNames, long headerEndOffset) {
    }

    /** 首行列名映射:列名必须与表列名匹配;缺失的非主键列按 NULL 写入(FR-605)。 */
    private static Header readHeader(Path file, TableMeta table) throws IOException {
        CsvParser.Record header;
        try (InputStream raw = new FileInputStream(file.toFile());
             CsvParser parser = new CsvParser(raw, 0)) {
            header = parser.next();
        }
        if (header == null) {
            throw new ImportFailure("CSV 文件为空(至少要有一行列名)");
        }
        List<String> names = new ArrayList<>(header.fields().size());
        Set<String> seen = new HashSet<>();
        List<String> unknown = new ArrayList<>();
        for (CsvParser.Field field : header.fields()) {
            String name = field.isNull() ? "" : field.value().trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                throw new ImportFailure("CSV 表头有空列名");
            }
            if (!seen.add(name)) {
                throw new ImportFailure("CSV 表头列名重复: " + name);
            }
            Column column = table.column(name);
            if (column == null) {
                unknown.add(name);
                continue;
            }
            names.add(column.name());
        }
        if (!unknown.isEmpty()) {
            throw new ImportFailure("CSV 列名与表 " + table.name() + " 不匹配:CSV 多出 "
                    + unknown + ";表列 = " + columnNames(table));
        }
        Column pk = table.primaryKey();
        if (!names.contains(pk.name())) {
            throw new ImportFailure("CSV 缺少主键列 " + pk.name() + ";CSV 列 = " + names);
        }
        List<String> missing = new ArrayList<>();
        for (Column column : table.columns()) {
            if (!names.contains(column.name())) {
                missing.add(column.name());
            }
        }
        if (!missing.isEmpty()) {
            System.out.println("提示:CSV 未提供列 " + missing + ",按 NULL 写入");
        }
        return new Header(names, names, header.endOffset());
    }

    private static List<String> columnNames(TableMeta table) {
        List<String> out = new ArrayList<>(table.columnCount());
        for (Column column : table.columns()) {
            out.add(column.name());
        }
        return out;
    }

    private static long estimateRows(Path file) {
        try (InputStream in = new FileInputStream(file.toFile())) {
            long lines = CsvParser.estimateLines(in);
            return Math.max(0, lines - 1); // 去掉表头
        } catch (IOException e) {
            return 0;
        }
    }

    /** 导入失败(带行号 / 列名 / 原值)。 */
    public static final class ImportFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ImportFailure(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------ 参数

    /** 命令行参数。 */
    static final class Options {
        String config = FeConfig.DEFAULT_PATH;
        String host;
        int port;
        String database;
        String table;
        String file;
        int batch;
        boolean restart;
        boolean help;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--config" -> options.config = next(args, ++i, arg);
                    case "--host" -> options.host = next(args, ++i, arg);
                    case "--port" -> options.port = Integer.parseInt(next(args, ++i, arg));
                    case "--database", "--db" -> options.database = next(args, ++i, arg);
                    case "--table" -> options.table = next(args, ++i, arg);
                    case "--file" -> options.file = next(args, ++i, arg);
                    case "--batch" -> options.batch = Integer.parseInt(next(args, ++i, arg));
                    case "--restart" -> options.restart = true;
                    case "-h", "--help" -> options.help = true;
                    default -> throw new IllegalArgumentException("未知参数: " + arg);
                }
            }
            if (!options.help) {
                if (options.table == null || options.table.isBlank()) {
                    throw new IllegalArgumentException("必须给出 --table");
                }
                if (options.file == null || options.file.isBlank()) {
                    throw new IllegalArgumentException("必须给出 --file");
                }
            }
            return options;
        }

        private static String next(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException(flag + " 缺少取值");
            }
            return args[index];
        }

        static String usage() {
            return """
                    用法: scripts/tools/csv-import --table <表> --file <csv> [选项]

                      --host <host>        BE 地址(默认取 config/fe.conf)
                      --port <port>        BE gRPC 端口(默认取 config/fe.conf)
                      --database, --db <db> 目标库(默认 default)
                      --table <表>         目标表(必填)
                      --file <csv>         CSV 文件(首行列名,必填)
                      --batch <n>          每批行数(默认取 csv_import_batch_rows)
                      --config <path>      配置文件(默认 config/fe.conf)
                      --restart            忽略断点,从头重跑(仍按主键去重)
                    """;
        }
    }
}
