package DataConnection;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2 — havuz yapılandırması ve tembel kurulum. Hiçbir test gerçek bir
 * veritabanı bağlantısı açmaz; yalnız {@link Db#buildHikariConfig} incelenir.
 */
class DbTest {

    private static final Function<String, String> NO_SYS = k -> null;
    private static final Function<String, String> NO_ENV = k -> null;

    private static DbConfig config() {
        Properties p = new Properties();
        p.setProperty(DbConfig.KEY_URL, "jdbc:mysql://127.0.0.1:3306/posdb?useSSL=false");
        p.setProperty(DbConfig.KEY_USER, "budget_app");
        p.setProperty(DbConfig.KEY_PASSWORD, "s3cr3t");
        p.setProperty(DbConfig.KEY_POOL_MAX, "15");
        p.setProperty(DbConfig.KEY_POOL_MIN_IDLE, "3");
        return DbConfig.load(p, NO_SYS, NO_ENV);
    }

    @Test
    void poolDoesNotFailWhenDatabaseIsOffline() {
        HikariConfig cfg = Db.buildHikariConfig(config());
        // -1: havuz kurulumu bağlantı denemesi yapmaz; MySQL kapalıyken de nesne oluşur.
        assertEquals(-1L, cfg.getInitializationFailTimeout());
        assertEquals(-1L, Db.INITIALIZATION_FAIL_TIMEOUT_MS);
    }

    @Test
    void connectionTimeoutIsBoundedBelowRetryCadence() {
        HikariConfig cfg = Db.buildHikariConfig(config());
        assertEquals(2_500L, cfg.getConnectionTimeout(), "Hikari varsayılanı ~30 sn olmamalı");
        assertTrue(cfg.getConnectionTimeout() < 3_000L,
                "bağlantı beklemesi 3 sn'lik retry aralığının altında kalmalı");
        assertTrue(cfg.getValidationTimeout() <= cfg.getConnectionTimeout(),
                "validationTimeout connectionTimeout'u aşmamalı");
    }

    @Test
    void poolSizingAndIdentityArePreserved() {
        HikariConfig cfg = Db.buildHikariConfig(config());
        assertEquals(15, cfg.getMaximumPoolSize());
        assertEquals(3, cfg.getMinimumIdle());
        assertEquals("budgetController", cfg.getPoolName());
        assertEquals("jdbc:mysql://127.0.0.1:3306/posdb?useSSL=false", cfg.getJdbcUrl());
        assertEquals("budget_app", cfg.getUsername());
    }

    @Test
    void statementCachePropertiesArePreserved() {
        Properties ds = Db.buildHikariConfig(config()).getDataSourceProperties();
        assertEquals("true", ds.getProperty("cachePrepStmts"));
        assertEquals("250", ds.getProperty("prepStmtCacheSize"));
    }

    @Test
    void classInitializationDoesNotBuildThePool() throws Exception {
        // Eski davranışta Db sınıfının yüklenmesi havuzu kurardı ve yapılandırma
        // yokken ExceptionInInitializerError ile patlardı. Tembel kurulumda
        // sınıfı başlatmak (ve config'e dokunmayan API'leri çağırmak) güvenlidir.
        Class<?> loaded = Class.forName("DataConnection.Db", true, DbTest.class.getClassLoader());
        assertNotNull(loaded);
        assertNotNull(Db.externalConfigPath());
        assertTrue(Db.externalConfigPath().toString().endsWith("db.properties"));
    }

    @Test
    void urlSecretsAreMaskedForLogs() {
        String masked = Db.maskUrlSecrets("jdbc:mysql://h/posdb?user=budget_app&password=s3cr3t");
        assertFalse(masked.contains("s3cr3t"), "parola maskelenmeli: " + masked);
    }
}
