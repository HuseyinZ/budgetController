package service.db.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Versiyonlu şema migration runner'ı (framework'süz, ~150 satır).
 *
 * <p>Tasarım:
 * <ul>
 *   <li>{@code schema_version(version PK, description, checksum, applied_at)}.</li>
 *   <li>Uygulanmış bir migration'ın checksum'u değiştiyse HER işlem FAIL eder
 *       (tarih yeniden yazılamaz).</li>
 *   <li>Bir migration'ın TÜM ifadeleri başarılı olmadan versiyon kaydı yazılmaz.
 *       MySQL DDL transactional olmadığından yarıda kalan migration kısmi şema
 *       bırakabilir — bu yüzden migration'lar idempotent yazılır ve hata
 *       mesajı hangi ifadede durulduğunu söyler.</li>
 *   <li>Bağlantıyı çağıran verir; runtime kullanıcısı değil, DDL yetkili
 *       migration kullanıcısı ({@code budget_migrate}) beklenir.</li>
 * </ul>
 */
public final class SchemaMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMigrator.class);

    public static final String VERSION_TABLE = "schema_version";

    private static final String DDL_VERSION_TABLE =
            "CREATE TABLE IF NOT EXISTS " + VERSION_TABLE + " ("
                    + " version     INT          NOT NULL,"
                    + " description VARCHAR(200) NOT NULL,"
                    + " checksum    CHAR(64)     NOT NULL,"
                    + " applied_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + " PRIMARY KEY (version)"
                    + ")";

    /** Uygulanmış kayıt. */
    public record Applied(int version, String description, String checksum, String appliedAt) {}

    /**
     * Durum özeti — SALT OKUMA ile üretilir.
     *
     * @param metadataInitialized {@code schema_version} tablosu var mı (yoksa DB hiç
     *                            versiyonlanmamış: tüm migration'lar bekleyen sayılır)
     */
    public record Status(boolean metadataInitialized, List<Applied> applied,
                         List<Migration> pending, List<String> checksumMismatches) {
        public boolean isUpToDate() {
            return metadataInitialized && pending.isEmpty() && checksumMismatches.isEmpty();
        }
    }

    /** Uygulama sonucu. */
    public record ApplyResult(List<Integer> appliedNow) {}

    /** Kontrollü hata — mesaj yalnız versiyon/ifade indeksi içerir, veri içermez. */
    public static final class MigrationException extends RuntimeException {
        public MigrationException(String message) { super(message); }
        public MigrationException(String message, Throwable cause) { super(message, cause); }
    }

    private final Connection connection;
    private final List<Migration> migrations;

    public SchemaMigrator(Connection connection, List<Migration> migrations) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.migrations = List.copyOf(Objects.requireNonNull(migrations, "migrations"));
    }

    // ------------------------------------------------------------------

    /** MUTASYON — yalnız apply() ve başarılı adoption çağırır (paket-içi). */
    void ensureVersionTable() {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate(DDL_VERSION_TABLE);
        } catch (SQLException e) {
            throw new MigrationException("schema_version tablosu hazırlanamadı (SQLState="
                    + e.getSQLState() + ")", e);
        }
    }

    /** schema_version okur; tablo yoksa boş. */
    public Map<Integer, Applied> readApplied() {
        Map<Integer, Applied> out = new LinkedHashMap<>();
        if (!versionTableExists()) return out;
        String sql = "SELECT version, description, checksum, applied_at FROM " + VERSION_TABLE
                + " ORDER BY version";
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getInt(1), new Applied(rs.getInt(1), rs.getString(2), rs.getString(3),
                        String.valueOf(rs.getObject(4))));
            }
        } catch (SQLException e) {
            throw new MigrationException("schema_version okunamadı (SQLState=" + e.getSQLState() + ")", e);
        }
        return out;
    }

    /**
     * Durum — KESİNLİKLE salt okuma: CREATE/INSERT/UPDATE/DDL yapmaz.
     * Runtime (DDL yetkisiz) kullanıcısıyla çağrılabilir. {@code schema_version}
     * yoksa {@code metadataInitialized=false} + tüm migration'lar bekleyen.
     */
    public Status status() {
        boolean initialized = versionTableExists();
        Map<Integer, Applied> applied = initialized ? readApplied() : Collections.emptyMap();
        List<Migration> pending = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (Migration m : migrations) {
            Applied a = applied.get(m.version());
            if (a == null) {
                pending.add(m);
            } else if (!a.checksum().equalsIgnoreCase(m.checksum())) {
                mismatches.add("V" + m.version() + " (" + m.description() + ")");
            }
        }
        return new Status(initialized, new ArrayList<>(applied.values()), pending, mismatches);
    }

    /**
     * Bekleyen migration'ları sırayla uygular (MUTASYON — migrate kullanıcısı).
     * {@code schema_version} tablosu yalnız burada (veya başarılı adoption'da) yaratılır.
     *
     * @throws MigrationException checksum uyuşmazlığı (hiçbir şey çalıştırılmaz)
     *                            veya bir ifade hatası (o migration için kayıt yazılmaz)
     */
    public ApplyResult apply() {
        Status status = status(); // salt okuma
        if (!status.checksumMismatches().isEmpty()) {
            throw new MigrationException("Uygulanmış migration içeriği değişmiş — durduruldu: "
                    + status.checksumMismatches());
        }
        if (status.pending().isEmpty()) {
            return new ApplyResult(List.of());
        }
        ensureVersionTable();
        List<Integer> appliedNow = new ArrayList<>();
        for (Migration m : status.pending()) {
            applyOne(m);
            appliedNow.add(m.version());
        }
        return new ApplyResult(Collections.unmodifiableList(appliedNow));
    }

    private void applyOne(Migration m) {
        List<String> statements = SqlScript.splitStatements(m.sql());
        LOG.info("Migration uygulanıyor: {} ({} ifade)", m, statements.size());
        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(true); // DDL implicit-commit; ifade bazlı ilerle
        } catch (SQLException e) {
            throw new MigrationException("Bağlantı autocommit ayarlanamadı", e);
        }
        try {
            int idx = 0;
            for (String stmt : statements) {
                idx++;
                try (Statement st = connection.createStatement()) {
                    st.execute(stmt);
                } catch (SQLException e) {
                    throw new MigrationException("Migration " + m + " ifade #" + idx + "/"
                            + statements.size() + " başarısız (SQLState=" + e.getSQLState()
                            + ", vendorCode=" + e.getErrorCode() + ") — versiyon kaydı YAZILMADI", e);
                }
            }
            recordApplied(m); // yalnız tüm ifadeler başarılıysa
            LOG.info("Migration tamamlandı: {}", m);
        } finally {
            try { connection.setAutoCommit(previousAutoCommit); } catch (SQLException ignore) { }
        }
    }

    /** Versiyon kaydı yazar (adoption da kullanır). */
    void recordApplied(Migration m) {
        String sql = "INSERT INTO " + VERSION_TABLE + " (version, description, checksum) VALUES (?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, m.version());
            ps.setString(2, m.description());
            ps.setString(3, m.checksum());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MigrationException("schema_version kaydı yazılamadı: V" + m.version()
                    + " (SQLState=" + e.getSQLState() + ")", e);
        }
    }

    boolean versionTableExists() {
        // MySQL "TABLE", H2 2.x "BASE TABLE" raporlar — ikisi de kabul
        try (ResultSet rs = connection.getMetaData().getTables(null, null, "%",
                new String[]{"TABLE", "BASE TABLE"})) {
            while (rs.next()) {
                if (VERSION_TABLE.equalsIgnoreCase(rs.getString("TABLE_NAME"))) return true;
            }
            return false;
        } catch (SQLException e) {
            throw new MigrationException("Tablo listesi okunamadı (SQLState=" + e.getSQLState() + ")", e);
        }
    }

    public List<Migration> migrations() { return migrations; }
}
