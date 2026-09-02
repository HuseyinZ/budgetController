package tools;

import DataConnection.DbConfig;
import service.db.migration.BaselineExpectations;
import service.db.migration.JdbcSchemaInspector;
import service.db.migration.Migration;
import service.db.migration.MigrationDiscovery;
import service.db.migration.SchemaAdoption;
import service.db.migration.SchemaMigrator;
import service.util.Mask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/**
 * Şema migration CLI'si — yönetici tarafından kurulum/upgrade anında çalıştırılır.
 *
 * <pre>
 *   java -cp budgetController.jar tools.Migrate --status          # salt okuma, runtime credential yeter
 *   java -cp budgetController.jar tools.Migrate --apply           # MUTASYON — migrate credential ZORUNLU
 *   java -cp budgetController.jar tools.Migrate --adopt-existing  # MUTASYON — migrate credential ZORUNLU
 * </pre>
 *
 * <p><b>Credential ayrımı:</b> {@code --apply} ve {@code --adopt-existing} için
 * {@code db.migrate.user / DB_MIGRATE_USER} ve {@code db.migrate.password / DB_MIGRATE_PASS}
 * açıkça verilmek ZORUNDADIR; yoksa FAIL. Runtime {@code db.user/db.password}
 * (budget_app) değerlerine ASLA sessizce düşülmez. URL ortaktır ({@code db.url}).
 * {@code --status} salt okuma olduğundan runtime credential ile çalışır.
 *
 * <p>Bu araç {@code DataConnection.Db}'yi YÜKLEMEZ (Hikari havuzu açılmaz); tek kısa
 * ömürlü JDBC bağlantısı kullanır. Hiçbir bakım/veri düzeltme işlemi çalıştırmaz.
 */
public final class Migrate {

    static final int EXIT_OK = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_USAGE = 2;

    /** Anahtar adları DbConfig'te tanımlı — burada yalnız takma ad (tek kaynak). */
    static final String KEY_MIGRATE_USER = DbConfig.KEY_MIGRATE_USER;
    static final String KEY_MIGRATE_PASSWORD = DbConfig.KEY_MIGRATE_PASSWORD;
    static final String ENV_MIGRATE_USER = DbConfig.ENV_MIGRATE_USER;
    static final String ENV_MIGRATE_PASSWORD = DbConfig.ENV_MIGRATE_PASSWORD;

    private Migrate() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length != 1 || !List.of("--status", "--apply", "--adopt-existing").contains(args[0])) {
            usage();
            return EXIT_USAGE;
        }
        String cmd = args[0];
        boolean mutating = !"--status".equals(cmd);

        List<Migration> migrations;
        try {
            migrations = MigrationDiscovery.discover();
        } catch (RuntimeException ex) {
            System.err.println("HATA: migration'lar keşfedilemedi: " + ex.getMessage());
            return EXIT_ERROR;
        }
        if (migrations.isEmpty()) {
            System.err.println("HATA: classpath'te db/migration/V###__*.sql bulunamadı");
            return EXIT_ERROR;
        }

        ConnectionSpec spec;
        try {
            spec = resolveConnection(loadUserProperties(), System::getProperty, System::getenv, mutating);
        } catch (DbConfig.MissingConfigException ex) {
            System.err.println("HATA: " + ex.getMessage());
            return EXIT_ERROR;
        }
        System.out.println("Bağlantı: " + Mask.urlSecrets(spec.url) + " kullanıcı=" + Mask.user(spec.user)
                + (mutating ? " (migrate credential)" : " (salt okuma)"));

        try (Connection c = DriverManager.getConnection(spec.url, spec.user, spec.password)) {
            SchemaMigrator migrator = new SchemaMigrator(c, migrations);
            switch (cmd) {
                case "--status" -> {
                    printStatus(migrator.status());
                    return EXIT_OK;
                }
                case "--apply" -> {
                    SchemaMigrator.ApplyResult r = migrator.apply();
                    System.out.println(r.appliedNow().isEmpty()
                            ? "Şema güncel — uygulanacak migration yok."
                            : "Uygulandı: " + r.appliedNow());
                    printStatus(migrator.status());
                    return EXIT_OK;
                }
                default -> {
                    int v = SchemaAdoption.adopt(c, new JdbcSchemaInspector(c),
                            migrations, BaselineExpectations.baseline());
                    System.out.println("Adoption tamam — V" + v + " uygulanmış işaretlendi (şema/veri değiştirilmedi).");
                    System.out.println("Sonraki adım: seed migration'ları için --apply çalıştırın.");
                    printStatus(migrator.status());
                    return EXIT_OK;
                }
            }
        } catch (SchemaMigrator.MigrationException ex) {
            System.err.println("HATA: " + ex.getMessage());
            return EXIT_ERROR;
        } catch (SQLException ex) {
            System.err.println("HATA: veritabanına bağlanılamadı (SQLState=" + ex.getSQLState()
                    + ", vendorCode=" + ex.getErrorCode() + ")");
            return EXIT_ERROR;
        }
    }

    private static void printStatus(SchemaMigrator.Status s) {
        if (!s.metadataInitialized()) {
            System.out.println("Migration metadata başlatılmamış (schema_version tablosu yok).");
            System.out.println("  Boş DB → --apply ; mevcut elle kurulmuş DB → --adopt-existing");
        }
        System.out.println("Uygulanmış:");
        if (s.applied().isEmpty()) System.out.println("  (yok)");
        for (SchemaMigrator.Applied a : s.applied()) {
            System.out.printf("  V%-4d %-40s %s%n", a.version(), a.description(), a.appliedAt());
        }
        System.out.println("Bekleyen:");
        if (s.pending().isEmpty()) System.out.println("  (yok)");
        for (Migration m : s.pending()) {
            System.out.printf("  V%-4d %s%n", m.version(), m.description());
        }
        if (!s.checksumMismatches().isEmpty()) {
            System.out.println("UYARI — checksum uyuşmazlığı (uygulanmış migration değiştirilmiş): "
                    + s.checksumMismatches());
        }
        System.out.println(s.isUpToDate() ? "Durum: GÜNCEL" : "Durum: GÜNCEL DEĞİL");
    }

    private static void usage() {
        System.err.println("Kullanım: tools.Migrate --status | --apply | --adopt-existing");
    }

    // ------------------------------------------------------------------

    record ConnectionSpec(String url, String user, String password) {}

    /**
     * Bağlantı bilgisi — çözümleme/öncelik mantığı TAMAMEN {@link DbConfig#loadFor};
     * Migrate yalnız hangi kimlik ROLÜNÜN gerektiğini seçer:
     * mutasyon → {@link DbConfig.Role#MIGRATION}, salt okuma → {@link DbConfig.Role#RUNTIME}.
     */
    static ConnectionSpec resolveConnection(Properties file, Function<String, String> sys,
                                            Function<String, String> env, boolean mutating) {
        DbConfig cfg = DbConfig.loadFor(mutating ? DbConfig.Role.MIGRATION : DbConfig.Role.RUNTIME,
                file, sys, env);
        return new ConnectionSpec(cfg.jdbcUrl(), cfg.username(), cfg.password());
    }

    private static Properties loadUserProperties() {
        Properties p = new Properties();
        Path path = Path.of(System.getProperty("user.home"), ".budget", "db.properties");
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            } catch (IOException e) {
                System.err.println("UYARI: " + path.getFileName() + " okunamadı");
            }
        }
        return p;
    }

}
