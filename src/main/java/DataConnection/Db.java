package DataConnection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class Db {
    private static final String URL = "jdbc:mysql://127.0.0.1:3306/?user=root";
    private static final String USER = "root";
    private static final String PASSWORD = "1234";

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
    // DataConnection.Db içine
    public static <T> T tx(java.util.function.Function<Connection, T> work) {
        try (Connection c = getConnection()) {
            boolean old = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T out = work.apply(c);
                c.commit();
                return out;
            } catch (Exception e) {
                c.rollback();
                throw new RuntimeException(e);
            } finally {
                c.setAutoCommit(old);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

}
/*// DataConnection/Db.java
package DataConnection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

public final class Db {
    private static final HikariDataSource DS;

    static {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getProperty("DB_URL", "jdbc:mysql://localhost:3306/yourdb?useSSL=false&serverTimezone=UTC"));
        cfg.setUsername(System.getProperty("DB_USER", "root"));
        cfg.setPassword(System.getProperty("DB_PASS", ""));
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        cfg.setPoolName("budgetController");
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        DS = new HikariDataSource(cfg);
    }

    private Db() {}

    public static Connection getConnection() throws SQLException {
        return DS.getConnection();
    }
}
*/