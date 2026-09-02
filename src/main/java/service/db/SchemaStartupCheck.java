package service.db;

import DataConnection.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.db.migration.Migration;
import service.db.migration.MigrationDiscovery;
import service.db.migration.SchemaMigrator;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Uygulama açılışında SALT OKUMA şema doğrulaması (C1-c).
 *
 * <p>Runtime hiçbir DDL/DML çalıştırmaz; yalnız {@link SchemaMigrator#status()}
 * (metadata + {@code SELECT schema_version}) okur. Şema güncel değilse uygulama
 * normal çalışmaya DEVAM ETMEZ — yönetici {@code tools.Migrate} ile şemayı hazırlar.
 * Runtime kullanıcısı ({@code budget_app}) için DDL yetkisi gerekmez.
 *
 * <p>Uygulamanın mevcut bağlantı havuzunu ({@link Db}) kullanır; ikinci havuz açmaz.
 */
public final class SchemaStartupCheck {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaStartupCheck.class);

    /** Kullanıcıya/log'a giden sabit yönlendirme mesajı. */
    public static final String MESSAGE =
            "Database schema is not ready. Run Migrate --status / --apply / --adopt-existing.";

    /** @param detail teknik olmayan, veri içermeyen kısa açıklama (log ve dialog için) */
    public record Result(boolean ok, String detail) {
        static Result pass() { return new Result(true, "schema up to date"); }
        static Result fail(String detail) { return new Result(false, detail); }
    }

    private SchemaStartupCheck() {}

    /** Saf doğrulama — test edilebilir; bağlantıyı çağıran verir, kapatmaz. */
    public static Result verify(Connection connection, List<Migration> migrations) {
        Objects.requireNonNull(connection, "connection");
        if (migrations == null || migrations.isEmpty()) {
            return Result.fail("no migrations found on classpath (db/migration)");
        }
        SchemaMigrator.Status status = new SchemaMigrator(connection, migrations).status(); // salt okuma
        if (!status.metadataInitialized()) {
            return Result.fail("schema_version table missing (database not migrated or not adopted)");
        }
        if (!status.checksumMismatches().isEmpty()) {
            return Result.fail("applied migration content changed: " + status.checksumMismatches());
        }
        if (!status.pending().isEmpty()) {
            return Result.fail("pending migrations: " + status.pending().stream()
                    .map(m -> "V" + m.version()).toList());
        }
        return Result.pass();
    }

    /**
     * Uygulama havuzundan tek bağlantı alıp doğrular. Bağlantı/yapılandırma hatasında
     * da {@code ok=false} döner (istisna yayılmaz); detay hassas veri içermez.
     */
    public static Result verifyUsingAppPool() {
        List<Migration> migrations;
        try {
            migrations = MigrationDiscovery.discover();
        } catch (RuntimeException ex) {
            return Result.fail("migration discovery failed (" + ex.getClass().getSimpleName() + ")");
        }
        try (Connection c = Db.getConnection()) {
            Result r = verify(c, migrations);
            if (r.ok()) {
                LOG.info("Schema check OK (V{} applied)", migrations.get(migrations.size() - 1).version());
            } else {
                LOG.error("Schema check FAILED: {} — {}", r.detail(), MESSAGE);
            }
            return r;
        } catch (SQLException ex) {
            LOG.error("Schema check: database unreachable (SQLState={}, vendorCode={})",
                    ex.getSQLState(), ex.getErrorCode());
            return Result.fail("database unreachable (SQLState=" + ex.getSQLState() + ")");
        }
    }
}
