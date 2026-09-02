package service.db.migration;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runner davranışı — sunucusuz H2 (MODE=MySQL) ile; gerçek V001 çalıştırılmaz,
 * küçük sentetik migration'lar kullanılır (MySQL'e özgü DDL H2'de anlamsız).
 */
class SchemaMigratorTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:migrator_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        conn = ds.getConnection();
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = conn.createStatement()) { st.execute("DROP ALL OBJECTS"); }
        conn.close();
    }

    private static Migration m(int v, String name, String sql) {
        return Migration.fromFile("V" + v + "__" + name + ".sql", sql);
    }

    @Test
    void appliesPendingInOrderAndRecordsVersions() throws Exception {
        List<Migration> ms = List.of(
                m(1, "create_t", "CREATE TABLE t (id INT PRIMARY KEY, n VARCHAR(10));"),
                m(2, "seed_t", "INSERT INTO t VALUES (1, 'a'); INSERT INTO t VALUES (2, 'b');"));
        SchemaMigrator mig = new SchemaMigrator(conn, ms);

        SchemaMigrator.ApplyResult r = mig.apply();
        assertEquals(List.of(1, 2), r.appliedNow());
        assertEquals(2, countRows("t"));

        Map<Integer, SchemaMigrator.Applied> applied = mig.readApplied();
        assertEquals(List.of(1, 2), List.copyOf(applied.keySet()));
        assertEquals(ms.get(0).checksum(), applied.get(1).checksum());
        assertTrue(mig.status().isUpToDate());
    }

    @Test
    void secondRunSkipsAlreadyApplied() throws Exception {
        List<Migration> ms = List.of(m(1, "create_t", "CREATE TABLE t (id INT PRIMARY KEY);"));
        new SchemaMigrator(conn, ms).apply();

        // Aynı migration tekrar: CREATE TABLE ikinci kez çalışsa patlardı → atlanmalı
        SchemaMigrator.ApplyResult r = new SchemaMigrator(conn, ms).apply();
        assertTrue(r.appliedNow().isEmpty(), "uygulanmış migration tekrar çalışmamalı");
        assertEquals(1, countRows(SchemaMigrator.VERSION_TABLE));
    }

    @Test
    void checksumMismatchFailsBeforeRunningAnything() throws Exception {
        new SchemaMigrator(conn, List.of(m(1, "a", "CREATE TABLE t (id INT);"))).apply();

        List<Migration> tampered = List.of(
                m(1, "a", "CREATE TABLE t (id BIGINT);"),          // içerik değişti
                m(2, "b", "CREATE TABLE u (id INT);"));            // beklemede
        SchemaMigrator mig = new SchemaMigrator(conn, tampered);
        assertFalse(mig.status().checksumMismatches().isEmpty());

        SchemaMigrator.MigrationException ex = assertThrows(SchemaMigrator.MigrationException.class, mig::apply);
        assertTrue(ex.getMessage().contains("V1"));
        assertFalse(tableExists("u"), "mismatch varken bekleyen migration ÇALIŞMAMALI");
        assertEquals(1, countRows(SchemaMigrator.VERSION_TABLE));
    }

    @Test
    void failedMigrationLeavesNoVersionRecord() throws Exception {
        List<Migration> ms = List.of(
                m(1, "ok", "CREATE TABLE t (id INT);"),
                m(2, "broken", "CREATE TABLE u (id INT); THIS IS NOT SQL;"),
                m(3, "never", "CREATE TABLE w (id INT);"));
        SchemaMigrator mig = new SchemaMigrator(conn, ms);

        SchemaMigrator.MigrationException ex = assertThrows(SchemaMigrator.MigrationException.class, mig::apply);
        assertTrue(ex.getMessage().contains("V2"), "hangi migration: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("#2/2"), "hangi ifade: " + ex.getMessage());

        Map<Integer, SchemaMigrator.Applied> applied = mig.readApplied();
        assertEquals(List.of(1), List.copyOf(applied.keySet()), "yalnız tam başarılı V1 kaydedilmeli");
        assertTrue(tableExists("u"), "kısmi etki kalabilir (DDL transactional değil) — belgelenmiş davranış");
        assertFalse(tableExists("w"), "sonraki migration başlamamalı");
    }

    @Test
    void statusIsStrictlyReadOnlyOnUninitializedDatabase() throws Exception {
        SchemaMigrator mig = new SchemaMigrator(conn, List.of(m(1, "a", "SELECT 1;"), m(2, "b", "SELECT 2;")));
        SchemaMigrator.Status s = mig.status();
        assertFalse(s.metadataInitialized(), "schema_version yok → metadata başlatılmamış");
        assertTrue(s.applied().isEmpty());
        assertEquals(2, s.pending().size());
        assertFalse(s.isUpToDate());
        assertFalse(tableExists(SchemaMigrator.VERSION_TABLE), "status() schema_version YARATMAMALI (salt okuma)");
        // İkinci çağrı da yaratmaz
        mig.status();
        assertFalse(tableExists(SchemaMigrator.VERSION_TABLE));
    }

    @Test
    void applyCreatesMetadataOnlyWhenThereIsWork() throws Exception {
        SchemaMigrator mig = new SchemaMigrator(conn, List.of(m(1, "a", "CREATE TABLE t (id INT);")));
        assertFalse(tableExists(SchemaMigrator.VERSION_TABLE));
        mig.apply();
        assertTrue(tableExists(SchemaMigrator.VERSION_TABLE), "apply schema_version'ı yaratır");
        assertTrue(mig.status().metadataInitialized());
    }

    // ---- yardımcılar ----
    private int countRows(String table) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) return true;
            }
        }
        return false;
    }
}
