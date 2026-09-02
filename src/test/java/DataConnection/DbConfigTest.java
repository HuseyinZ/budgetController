package DataConnection;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C4 — credential çözümleme testleri.
 *
 * <p>{@link DbConfig} DB'ye dokunmaz; {@code Db} sınıfı bilinçli olarak
 * yüklenmez (static initializer gerçek bağlantı kurar).
 */
class DbConfigTest {

    private static final Function<String, String> NONE = k -> null;

    private static Function<String, String> map(Map<String, String> m) {
        return m::get;
    }

    private static Properties props(String url, String user, String pass) {
        Properties p = new Properties();
        if (url != null) p.setProperty(DbConfig.KEY_URL, url);
        if (user != null) p.setProperty(DbConfig.KEY_USER, user);
        if (pass != null) p.setProperty(DbConfig.KEY_PASSWORD, pass);
        return p;
    }

    @Test
    void loadsValuesFromUserConfigFile() {
        DbConfig cfg = DbConfig.load(
                props("jdbc:mysql://localhost:3306/posdb", "budget_app", "file-secret"),
                NONE, NONE);
        assertEquals("jdbc:mysql://localhost:3306/posdb", cfg.jdbcUrl());
        assertEquals("budget_app", cfg.username());
        assertEquals("file-secret", cfg.password());
        assertEquals(10, cfg.maxPoolSize(), "pool varsayılanı");
        assertEquals(2, cfg.minIdle(), "minIdle varsayılanı");
    }

    @Test
    void systemPropertyBeatsEnvBeatsFile() {
        Properties file = props("jdbc:mysql://file/posdb", "file-user", "file-pass");
        Function<String, String> env = map(Map.of(
                DbConfig.ENV_URL, "jdbc:mysql://env/posdb",
                DbConfig.ENV_USER, "env-user",
                DbConfig.ENV_PASSWORD, "env-pass"));
        Function<String, String> sys = map(Map.of(DbConfig.KEY_USER, "sys-user"));

        DbConfig cfg = DbConfig.load(file, sys, env);
        assertEquals("sys-user", cfg.username(), "sistem özelliği en yüksek öncelik");
        assertEquals("jdbc:mysql://env/posdb", cfg.jdbcUrl(), "env dosyayı ezer");
        assertEquals("env-pass", cfg.password());
    }

    @Test
    void missingConfigFailsWithoutAnyDefaultAccount() {
        DbConfig.MissingConfigException ex = assertThrows(
                DbConfig.MissingConfigException.class,
                () -> DbConfig.load(new Properties(), NONE, NONE));
        assertEquals(java.util.List.of(DbConfig.KEY_URL, DbConfig.KEY_USER, DbConfig.KEY_PASSWORD),
                ex.missingKeys());
        String msg = ex.getMessage();
        assertTrue(msg.contains("db.user") && msg.contains("db.password"), "eksik anahtarlar listelenmeli");
        assertFalse(msg.contains("root"), "root fallback izi olmamalı");
        assertFalse(msg.contains("1234"), "eski varsayılan parola izi olmamalı");
    }

    @Test
    void partiallyMissingConfigReportsOnlyMissingKeys() {
        DbConfig.MissingConfigException ex = assertThrows(
                DbConfig.MissingConfigException.class,
                () -> DbConfig.load(props("jdbc:mysql://localhost/posdb", "budget_app", null), NONE, NONE));
        assertEquals(java.util.List.of(DbConfig.KEY_PASSWORD), ex.missingKeys());
    }

    @Test
    void blankValuesCountAsMissing() {
        assertThrows(DbConfig.MissingConfigException.class,
                () -> DbConfig.load(props("jdbc:mysql://localhost/posdb", "   ", "x"), NONE, NONE));
    }

    @Test
    void toStringNeverExposesSecrets() {
        DbConfig cfg = DbConfig.load(
                props("jdbc:mysql://localhost:3306/posdb?user=budget_app&password=topsecret",
                        "budget_app", "topsecret"),
                NONE, NONE);
        String s = cfg.toString();
        assertFalse(s.contains("topsecret"), "parola ne alanında ne URL'de görünmeli");
        assertTrue(s.contains("password=****"));
    }

    // ---------------- Role.MIGRATION (merkezi çözümleme) ----------------

    @Test
    void migrationRoleRequiresExplicitMigrateCredentials() {
        DbConfig.MissingConfigException ex = assertThrows(DbConfig.MissingConfigException.class,
                () -> DbConfig.loadFor(DbConfig.Role.MIGRATION,
                        props("jdbc:mysql://localhost/posdb", "budget_app", "app-secret"), NONE, NONE));
        assertEquals(java.util.List.of(DbConfig.KEY_MIGRATE_USER, DbConfig.KEY_MIGRATE_PASSWORD), ex.missingKeys());
        assertFalse(ex.getMessage().contains("app-secret"), "runtime parolası mesajda görünmemeli");
    }

    @Test
    void migrationRoleNeverFallsBackToRuntimeUser() {
        Properties p = props("jdbc:mysql://localhost/posdb", "budget_app", "app-secret");
        p.setProperty(DbConfig.KEY_MIGRATE_PASSWORD, "mig-secret"); // yalnız parola var
        DbConfig.MissingConfigException ex = assertThrows(DbConfig.MissingConfigException.class,
                () -> DbConfig.loadFor(DbConfig.Role.MIGRATION, p, NONE, NONE));
        assertEquals(java.util.List.of(DbConfig.KEY_MIGRATE_USER), ex.missingKeys());
    }

    @Test
    void migrationRoleUsesMigrateIdentityWithSharedUrl() {
        Properties p = props("jdbc:mysql://localhost/posdb", "budget_app", "app-secret");
        p.setProperty(DbConfig.KEY_MIGRATE_USER, "budget_migrate");
        p.setProperty(DbConfig.KEY_MIGRATE_PASSWORD, "mig-secret");
        DbConfig cfg = DbConfig.loadFor(DbConfig.Role.MIGRATION, p, NONE, NONE);
        assertEquals("budget_migrate", cfg.username());
        assertEquals("mig-secret", cfg.password());
        assertEquals("jdbc:mysql://localhost/posdb", cfg.jdbcUrl(), "URL runtime ile ortak");
        // RUNTIME rolü aynı dosyadan uygulama kimliğini verir — iki rol karışmaz
        assertEquals("budget_app", DbConfig.loadFor(DbConfig.Role.RUNTIME, p, NONE, NONE).username());
    }

    @Test
    void migrationRoleHonoursSamePrecedence() {
        Properties file = props("jdbc:mysql://localhost/posdb", "budget_app", "app-secret");
        file.setProperty(DbConfig.KEY_MIGRATE_USER, "file-mig");
        file.setProperty(DbConfig.KEY_MIGRATE_PASSWORD, "file-pass");
        Function<String, String> env = map(Map.of(DbConfig.ENV_MIGRATE_USER, "env-mig",
                DbConfig.ENV_MIGRATE_PASSWORD, "env-pass"));
        Function<String, String> sys = map(Map.of(DbConfig.KEY_MIGRATE_USER, "sys-mig"));
        DbConfig cfg = DbConfig.loadFor(DbConfig.Role.MIGRATION, file, sys, env);
        assertEquals("sys-mig", cfg.username(), "sys > env > file — runtime ile aynı kural");
        assertEquals("env-pass", cfg.password());
    }

    @Test
    void invalidPoolSettingIsRejected() {
        Properties p = props("jdbc:mysql://localhost/posdb", "budget_app", "x");
        p.setProperty(DbConfig.KEY_POOL_MAX, "0");
        assertThrows(IllegalStateException.class, () -> DbConfig.load(p, NONE, NONE));
    }
}
