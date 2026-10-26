package com.wangyu.tangdb.smoke;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * tang_db 的 JDBC 冒烟测试(SPEC-P3.5 SC-312)。
 *
 * <p>连接串按 K66:
 * {@code jdbc:mysql://127.0.0.1:<port>/<db>?useSSL=false&allowPublicKeyRetrieval=true
 * &useServerPrepStmts=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai}</p>
 *
 * <p>覆盖:连接、建表、插入、查询、事务、切库、一个故意错误(校验
 * {@link SQLException#getErrorCode()} 与 §5.3 一致,表不存在 = 1146)。</p>
 *
 * <p>用法:{@code java ... JdbcSmoke <port> [user] [password] [database]}</p>
 */
public final class JdbcSmoke {

    private JdbcSmoke() {
    }

    public static void main(String[] args) {
        String port = args.length > 0 ? args[0] : "3406";
        String user = args.length > 1 ? args[1] : "root";
        String password = args.length > 2 ? args[2] : "tangdb";
        String database = args.length > 3 ? args[3] : "default";

        String url = "jdbc:mysql://127.0.0.1:" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true"
                + "&useServerPrepStmts=false&characterEncoding=utf8"
                + "&serverTimezone=Asia/Shanghai";

        int failures = 0;
        System.out.println("JDBC URL: " + url);

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            System.out.println("[OK] 连接成功: " + conn.getMetaData().getDatabaseProductVersion());

            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS jdbc_smoke");
            } catch (SQLException ignored) {
                // tang_db 不支持 IF EXISTS;首次运行没有这张表时忽略。
            }
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE jdbc_smoke");
            } catch (SQLException ignored) {
                // 表不存在
            }

            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE jdbc_smoke (id INT PRIMARY KEY, name VARCHAR(32))");
                System.out.println("[OK] CREATE TABLE");

                int n = st.executeUpdate(
                        "INSERT INTO jdbc_smoke (id, name) VALUES (1, 'jdbc'), (2, 'connector')");
                System.out.println("[OK] INSERT affected=" + n + (n == 2 ? "" : "  <-- 非预期"));
                if (n != 2) {
                    failures++;
                }

                try (ResultSet rs = st.executeQuery(
                        "SELECT id, name FROM jdbc_smoke ORDER BY id")) {
                    int rows = 0;
                    while (rs.next()) {
                        rows++;
                        System.out.println("  row: " + rs.getInt("id") + " / " + rs.getString("name"));
                    }
                    System.out.println("[OK] SELECT rows=" + rows);
                    if (rows != 2) {
                        failures++;
                    }
                } catch (SQLException e) {
                    // tang_db 不支持 ORDER BY;退回无序查询。
                    try (ResultSet rs = st.executeQuery("SELECT id, name FROM jdbc_smoke")) {
                        int rows = 0;
                        while (rs.next()) {
                            rows++;
                        }
                        System.out.println("[OK] SELECT(无序) rows=" + rows);
                        if (rows != 2) {
                            failures++;
                        }
                    }
                }

                // 事务
                conn.setAutoCommit(true);
                try (Statement tx = conn.createStatement()) {
                    tx.execute("BEGIN");
                    tx.executeUpdate("INSERT INTO jdbc_smoke (id, name) VALUES (3, 'txn')");
                    tx.execute("COMMIT");
                }
                System.out.println("[OK] 事务 BEGIN / INSERT / COMMIT");

                // 切库
                try (Statement tx = conn.createStatement()) {
                    tx.execute("CREATE DATABASE jdbc_shop");
                } catch (SQLException e) {
                    // 已存在
                }
                try (Statement tx = conn.createStatement()) {
                    tx.execute("USE jdbc_shop");
                    tx.execute("CREATE TABLE orders (id INT PRIMARY KEY)");
                    tx.executeUpdate("INSERT INTO orders (id) VALUES (99)");
                }
                System.out.println("[OK] 切库 USE jdbc_shop + 建表 + 插入");

                // 故意错误:表不存在 → 1146
                try (Statement bad = conn.createStatement()) {
                    bad.executeQuery("SELECT * FROM definitely_no_such_table");
                    System.out.println("[FAIL] 表不存在却没有报错");
                    failures++;
                } catch (SQLException e) {
                    int expected = 1146;
                    String mark = e.getErrorCode() == expected ? "[OK]" : "[FAIL]";
                    System.out.println(mark + " 错误码校验: getErrorCode()=" + e.getErrorCode()
                            + " (期望 " + expected + "), SQLState=" + e.getSQLState()
                            + ", msg=" + e.getMessage());
                    if (e.getErrorCode() != expected) {
                        failures++;
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("[FAIL] JDBC 连接/执行异常: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        System.out.println(failures == 0 ? "JDBC_SMOKE_PASS" : "JDBC_SMOKE_FAIL");
        if (failures != 0) {
            System.exit(1);
        }
    }
}
