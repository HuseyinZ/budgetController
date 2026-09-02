package tools;

import DataConnection.DbConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Migrate CLI credential ayrımı — DB'siz, {@code DataConnection.Db} yüklenmez. */
class MigrateCredentialsTest {

    private static final Function<String, String> NONE = k -> null;

    private static Properties runtimeConfig() {
        Properties p = new Properties();
        p.setProperty(DbConfig.KEY_URL, "jdbc:mysql://localhost:3306/posdb");
        p.setProperty(DbConfig.KEY_USER, "budget_app");
        p.setProperty(DbConfig.KEY_PASSWORD, "app-secret");
        return p;
    }

    @Test
    void statusUsesRuntimeCredentials() {
        Migrate.ConnectionSpec s = Migrate.resolveConnection(runtimeConfig(), NONE, NONE, false);
        assertEquals("budget_app", s.user());
        assertEquals("app-secret", s.password());
        assertEquals("jdbc:mysql://localhost:3306/posdb", s.url());
    }

    @Test
    void mutatingCommandsFailWithoutExplicitMigrateCredentials() {
        DbConfig.MissingConfigException ex = assertThrows(DbConfig.MissingConfigException.class,
                () -> Migrate.resolveConnection(runtimeConfig(), NONE, NONE, true));
        assertEquals(List.of(Migrate.KEY_MIGRATE_USER, Migrate.KEY_MIGRATE_PASSWORD), ex.missingKeys());
        assertFalse(ex.getMessage().contains("app-secret"), "runtime parolası mesajda görünmemeli");
    }

    @Test
    void mutatingCommandsNeverFallBackToRuntimeUserWhenOnlyPasswordGiven() {
        Function<String, String> env = Map.of(Migrate.ENV_MIGRATE_PASSWORD, "mig-secret")::get;
        DbConfig.MissingConfigException ex = assertThrows(DbConfig.MissingConfigException.class,
                () -> Migrate.resolveConnection(runtimeConfig(), NONE, env, true));
        assertEquals(List.of(Migrate.KEY_MIGRATE_USER), ex.missingKeys());
    }

    @Test
    void mutatingCommandsUseMigrateCredentialsWithSharedUrl() {
        Properties file = runtimeConfig();
        file.setProperty(Migrate.KEY_MIGRATE_USER, "budget_migrate");
        file.setProperty(Migrate.KEY_MIGRATE_PASSWORD, "mig-secret");
        Migrate.ConnectionSpec s = Migrate.resolveConnection(file, NONE, NONE, true);
        assertEquals("budget_migrate", s.user());
        assertEquals("mig-secret", s.password());
        assertEquals("jdbc:mysql://localhost:3306/posdb", s.url(), "URL runtime config ile ortak");
    }

    @Test
    void systemPropertyBeatsEnvBeatsFileForMigrateCredentials() {
        Properties file = runtimeConfig();
        file.setProperty(Migrate.KEY_MIGRATE_USER, "file-mig");
        file.setProperty(Migrate.KEY_MIGRATE_PASSWORD, "file-pass");
        Function<String, String> env = Map.of(Migrate.ENV_MIGRATE_USER, "env-mig",
                Migrate.ENV_MIGRATE_PASSWORD, "env-pass")::get;
        Function<String, String> sys = Map.of(Migrate.KEY_MIGRATE_USER, "sys-mig")::get;
        Migrate.ConnectionSpec s = Migrate.resolveConnection(file, sys, env, true);
        assertEquals("sys-mig", s.user());
        assertEquals("env-pass", s.password());
    }

    @Test
    void usageErrorsDoNotTouchConfig() {
        assertEquals(Migrate.EXIT_USAGE, Migrate.run(new String[0]));
        assertEquals(Migrate.EXIT_USAGE, Migrate.run(new String[]{"--bogus"}));
    }
}
