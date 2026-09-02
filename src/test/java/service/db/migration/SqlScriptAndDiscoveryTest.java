package service.db.migration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DB'siz: SQL bölme, dosya adı deseni, keşif sıralaması, legacy yok sayma, checksum kararlılığı. */
class SqlScriptAndDiscoveryTest {

    // ---------------- SqlScript ----------------

    @Test
    void splitsOnSemicolonAndDropsComments() {
        String script = "-- baslik yorumu\nCREATE TABLE a (id INT); -- satir sonu yorumu\n\n"
                + "INSERT INTO a VALUES (1);\n-- son\n";
        List<String> stmts = SqlScript.splitStatements(script);
        assertEquals(List.of("CREATE TABLE a (id INT)", "INSERT INTO a VALUES (1)"), stmts);
    }

    @Test
    void semicolonInsideQuotesIsNotASeparator() {
        String script = "INSERT INTO t (n) VALUES ('a;b');INSERT INTO t (n) VALUES ('it''s;x');";
        List<String> stmts = SqlScript.splitStatements(script);
        assertEquals(2, stmts.size());
        assertEquals("INSERT INTO t (n) VALUES ('a;b')", stmts.get(0));
        assertEquals("INSERT INTO t (n) VALUES ('it''s;x')", stmts.get(1));
    }

    @Test
    void dashesInsideQuotesAreNotComments() {
        List<String> stmts = SqlScript.splitStatements("INSERT INTO t VALUES ('--not-a-comment');");
        assertEquals(List.of("INSERT INTO t VALUES ('--not-a-comment')"), stmts);
    }

    @Test
    void emptyAndWhitespaceOnlyProduceNothing() {
        assertTrue(SqlScript.splitStatements("").isEmpty());
        assertTrue(SqlScript.splitStatements(" ; ;\n-- x\n").isEmpty());
        assertTrue(SqlScript.splitStatements(null).isEmpty());
    }

    // ---------------- Migration naming / checksum ----------------

    @Test
    void fileNamePatternAcceptsOnlyVersionedMigrations() {
        assertNotNull(Migration.fromFile("V001__baseline_schema.sql", "x"));
        assertNotNull(Migration.fromFile("V12__add-index.sql", "x"));
        assertNull(Migration.fromFile("V2026_05_15__kitchen_printers.sql", "x"), "legacy tarih-adlı dosya eşleşmemeli");
        assertNull(Migration.fromFile("V001__baseline_schema.sql.bak", "x"));
        assertNull(Migration.fromFile("README.md", "x"));
        assertNull(Migration.fromFile("baseline.sql", "x"));
    }

    @Test
    void checksumIsLineEndingIndependent() {
        Migration lf = Migration.fromFile("V001__a.sql", "CREATE TABLE a (id INT);\nINSERT INTO a VALUES (1);\n");
        Migration crlf = Migration.fromFile("V001__a.sql", "CREATE TABLE a (id INT);\r\nINSERT INTO a VALUES (1);\r\n");
        assertEquals(lf.checksum(), crlf.checksum(), "CRLF/LF aynı checksum vermeli");
        assertEquals(64, lf.checksum().length(), "SHA-256 hex");
        Migration changed = Migration.fromFile("V001__a.sql", "CREATE TABLE a (id BIGINT);\n");
        assertTrue(!changed.checksum().equals(lf.checksum()), "içerik değişince checksum değişmeli");
    }

    // ---------------- Discovery ----------------

    @Test
    void discoveryOrdersNumericallyAndIgnoresNonMatching() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("V010__ten.sql", "SELECT 10");
        files.put("V2__two.sql", "SELECT 2");
        files.put("V001__one.sql", "SELECT 1");
        files.put("README.md", "docs");
        files.put("V2026_05_15__kitchen_printers.sql", "legacy");
        files.put("V001__one.sql.bak", "dup");
        List<Migration> found = MigrationDiscovery.fromFiles(files);
        assertEquals(List.of(1, 2, 10), found.stream().map(Migration::version).toList(),
                "numerik sıra (lexicographic değil) ve yalnız desene uyanlar");
    }

    @Test
    void duplicateVersionIsRejected() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("V001__a.sql", "SELECT 1");
        files.put("V1__b.sql", "SELECT 1");
        assertThrows(IllegalStateException.class, () -> MigrationDiscovery.fromFiles(files));
    }

    @Test
    void classpathDiscoveryFindsBaselineAndSeeds() {
        List<Migration> found = MigrationDiscovery.discover();
        assertEquals(List.of(1, 2, 3), found.stream().map(Migration::version).toList(),
                "src/main/resources/db/migration altında V001-V003 bulunmalı; legacy klasörü classpath'te değil");
        assertTrue(found.get(0).description().contains("baseline"));
        // Baseline SQL statement sözleşmesi: literal içinde ';' yok → 16 CREATE TABLE
        long creates = SqlScript.splitStatements(found.get(0).sql()).stream()
                .filter(s -> s.toUpperCase().startsWith("CREATE TABLE")).count();
        assertEquals(16, creates);
    }
}
