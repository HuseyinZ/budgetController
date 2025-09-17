package DataConnection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.function.Function;

public final class Db {
    private static final Path EXTERNAL_CONFIG_PATH = Path.of(System.getProperty("user.home"), ".budget", "db.properties");
    private static final HikariDataSource DS;
    private static final Properties CONFIG_SNAPSHOT = new Properties();

    static {
        Properties props = loadProperties();

        String jdbcUrl = resolve("db.url", "DB_URL", props,
                "jdbc:mysql://127.0.0.1:3306/budget?useSSL=false&serverTimezone=UTC");
        String username = resolve("db.user", "DB_USER", props, "root");
        String password = resolve("db.password", "DB_PASS", props, "");
        int maxPool = Integer.parseInt(resolve("db.pool.maxSize", "DB_POOL_MAX", props, "10"));
        int minIdle = Integer.parseInt(resolve("db.pool.minIdle", "DB_POOL_MIN_IDLE", props, "2"));

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(maxPool);
        cfg.setMinimumIdle(Math.min(minIdle, maxPool));
        cfg.setPoolName("budgetController");
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        DS = new HikariDataSource(cfg);

        CONFIG_SNAPSHOT.setProperty("db.url", jdbcUrl);
        CONFIG_SNAPSHOT.setProperty("db.user", username);
        CONFIG_SNAPSHOT.setProperty("db.password", password);
        CONFIG_SNAPSHOT.setProperty("db.pool.maxSize", Integer.toString(maxPool));
        CONFIG_SNAPSHOT.setProperty("db.pool.minIdle", Integer.toString(Math.min(minIdle, maxPool)));

        Runtime.getRuntime().addShutdownHook(new Thread(DS::close, "budgetController-hikari-shutdown"));
    }

    private Db() {
    }

    public static Connection getConnection() throws SQLException {
        return DS.getConnection();
    }

    public static DataSource getDataSource() {
        return DS;
    }

    public static Path externalConfigPath() {
        return EXTERNAL_CONFIG_PATH;
    }

    public static Properties currentConfiguration() {
        Properties copy = new Properties();
        copy.putAll(CONFIG_SNAPSHOT);
        return copy;
    }

    public static <T> T tx(Function<Connection, T> work) {
        try (Connection c = getConnection()) {
            boolean old = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T out = work.apply(c);
                c.commit();
                return out;
            } catch (Exception e) {
                c.rollback();
                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
            } finally {
                c.setAutoCommit(old);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Properties loadProperties() {
        Properties props = new Properties();

        try (InputStream in = Db.class.getResourceAsStream("/db.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }

        if (Files.exists(EXTERNAL_CONFIG_PATH)) {
            try (InputStream in = Files.newInputStream(EXTERNAL_CONFIG_PATH)) {
                props.load(in);
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        return props;
    }

    private static String resolve(String propertyKey, String envKey, Properties props, String defaultValue) {
        String sys = System.getProperty(propertyKey);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env;
        }
        String fromProps = props.getProperty(propertyKey);
        if (fromProps != null && !fromProps.isBlank()) {
            return fromProps;
        }
        return defaultValue;
    }
}
