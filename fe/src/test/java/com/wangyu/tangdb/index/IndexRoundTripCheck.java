package com.wangyu.tangdb.index;

import com.wangyu.tangdb.protocol.mysql.MysqlClientConnection;

import java.util.List;

/**
 * SC-338 / K96:脚本级 SHOW CREATE TABLE round-trip 校验。
 *
 * <p>从 fed 取 `SHOW CREATE TABLE src`,把表名替换成 dst 后原样喂回 fed;
 * 对比两表的列数与索引数,一致则打印 ROUNDTRIP_PASS。</p>
 */
public final class IndexRoundTripCheck {

    private IndexRoundTripCheck() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 3406;
        String user = args.length > 1 ? args[1] : "root";
        String password = args.length > 2 ? args[2] : "tangdb";
        String db = args.length > 3 ? args[3] : "default";
        try (MysqlClientConnection conn = MysqlClientConnection.connect(
                "127.0.0.1", port, user, password, db)) {
            drop(conn, "rt_src");
            drop(conn, "rt_dst");
            conn.query("CREATE TABLE rt_src (id INT PRIMARY KEY, code VARCHAR(16), note VARCHAR(16))");
            conn.query("CREATE UNIQUE INDEX uk_rt_code ON rt_src (code)");
            conn.query("CREATE INDEX idx_rt_note ON rt_src (note)");

            MysqlClientConnection.Response create = conn.query("SHOW CREATE TABLE rt_src");
            String ddl = (String) create.rows().get(0)[1];
            String copy = ddl.replaceFirst("CREATE TABLE `rt_src`", "CREATE TABLE `rt_dst`");
            conn.query(copy);

            int srcColumns = columnCount(conn, "rt_src");
            int dstColumns = columnCount(conn, "rt_dst");
            int srcIndexes = indexCount(conn, "rt_src");
            int dstIndexes = indexCount(conn, "rt_dst");

            System.out.println("SHOW CREATE TABLE 输出:");
            System.out.println(ddl);
            System.out.println("src: columns=" + srcColumns + " indexes=" + srcIndexes);
            System.out.println("dst: columns=" + dstColumns + " indexes=" + dstIndexes);
            if (srcColumns == dstColumns && srcIndexes == dstIndexes && dstColumns > 0) {
                System.out.println("ROUNDTRIP_PASS");
            } else {
                System.out.println("ROUNDTRIP_FAIL");
                System.exit(1);
            }
        }
    }

    private static void drop(MysqlClientConnection conn, String table) throws Exception {
        try {
            conn.query("DROP TABLE " + table);
        } catch (RuntimeException ignored) {
            // 首次运行不存在。
        }
    }

    private static int columnCount(MysqlClientConnection conn, String table) throws Exception {
        MysqlClientConnection.Response response = conn.query(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_NAME = '" + table + "'");
        return response.rows().size();
    }

    private static int indexCount(MysqlClientConnection conn, String table) throws Exception {
        MysqlClientConnection.Response response = conn.query("SHOW INDEX FROM " + table);
        List<Object[]> rows = response.rows();
        return rows.size();
    }
}
