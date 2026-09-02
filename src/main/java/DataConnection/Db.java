package DataConnection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import service.util.Mask;

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
        // Öncelik: sistem özelliği > ortam değişkeni > ~/.budget/db.properties (> classpath).
        // Gömülü varsayılan hesap YOK — eksikse DbConfig.MissingConfigException fırlar
        // (mesaj yalnız eksik anahtar adlarını içerir; sessizce root'a düşülmez).
        DbConfig config = DbConfig.load(loadProperties(), System::getProperty, System::getenv);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(config.jdbcUrl());
        cfg.setUsername(config.username());
        cfg.setPassword(config.password());
        cfg.setMaximumPoolSize(config.maxPoolSize());
        cfg.setMinimumIdle(config.minIdle());
        cfg.setPoolName("budgetController");
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimiét", "2048");

        DS = new HikariDataSource(cfg);

        CONFIG_SNAPSHOT.setProperty(DbConfig.KEY_URL, config.jdbcUrl());
        CONFIG_SNAPSHOT.setProperty(DbConfig.KEY_USER, config.username());
        CONFIG_SNAPSHOT.setProperty(DbConfig.KEY_PASSWORD, config.password());
        CONFIG_SNAPSHOT.setProperty(DbConfig.KEY_POOL_MAX, Integer.toString(config.maxPoolSize()));
        CONFIG_SNAPSHOT.setProperty(DbConfig.KEY_POOL_MIN_IDLE, Integer.toString(config.minIdle()));

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

    /**
     * Mevcut konfigürasyonun MASKE'lenmiş bir kopyasını döner.
     *
     * <p>Şifre alanları her zaman {@code ****} olarak gösterilir; JDBC URL
     * içinde gömülü {@code user=...&password=...} parametreleri varsa onlar
     * da maskelenir. Bu metod log/debug ve UI için güvenlidir; gerçek şifre
     * hiçbir koşulda dışarı çıkmaz.
     */
    public static Properties currentConfiguration() {
        Properties copy = new Properties();
        copy.putAll(CONFIG_SNAPSHOT);
        copy.setProperty("db.password", "****");
        String url = copy.getProperty("db.url", "");
        copy.setProperty("db.url", maskUrlSecrets(url));
        return copy;
    }

    /**
     * URL içindeki user/password query parametrelerini maskele.
     * Gövde {@link Mask#urlSecrets(String)}'e taşındı (DB bootstrap'sız test
     * edilebilsin diye); bu metod geriye dönük uyumluluk için delege eder.
     */
    public static String maskUrlSecrets(String url) {
        return Mask.urlSecrets(url);
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

    /**
     * Yalnız dış config dosyasını okur: {@code ~/.budget/db.properties}.
     *
     * <p>Classpath'teki {@code db.properties} BİLİNÇLİ olarak okunmaz — JAR içine
     * paketlenebilecek bir kaynak dosyası credential taşımamalıdır. Dosya yoksa
     * boş Properties döner; eksik anahtarları {@link DbConfig} raporlar.
     */
    private static Properties loadProperties() {
        Properties props = new Properties();
        if (Files.exists(EXTERNAL_CONFIG_PATH)) {
            try (InputStream in = Files.newInputStream(EXTERNAL_CONFIG_PATH)) {
                props.load(in);
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        return props;
    }

}