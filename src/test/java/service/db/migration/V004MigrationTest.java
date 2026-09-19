package service.db.migration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V004 — masa düzeni temeli + ürün birimleri migration'ı.
 *
 * <p>Dosya içeriği ve seed sayıları doğrulanır; gerçek MySQL gerekmez.
 * Migration'ın gerçek sunucuda uygulanması CI'daki fresh-install job'ında
 * ayrıca doğrulanıyor.
 */
class V004MigrationTest {

    private static final Path DIR = Path.of("src", "main", "resources", "db", "migration");
    private static final String FILE = "V004__table_layout_and_product_units.sql";

    private static String sql;
    private static List<String> statements;

    @BeforeAll
    static void load() throws IOException {
        Path file = DIR.resolve(FILE);
        assertTrue(Files.exists(file), "V004 bulunamadı: " + file.toAbsolutePath());
        sql = Files.readString(file, StandardCharsets.UTF_8);
        statements = SqlScript.splitStatements(sql);
    }

    @Test
    void fileNameAndChecksumAreValid() {
        Migration m = Migration.fromFile(FILE, sql);
        assertEquals(4, m.version());
        assertEquals(64, m.checksum().length(), "SHA-256 onaltılık olmalı");
    }

    @Test
    void earlierMigrationsAreUntouched() throws IOException {
        // V001-V003 uygulanmış migration'lardır; içerikleri değişirse checksum
        // uyuşmazlığı oluşur. Bu test yalnız dosyaların varlığını ve V004'ün
        // onlara dokunmadığını (tek yeni dosya) sabitler.
        for (String name : List.of("V001__baseline_schema.sql", "V002__seed_roles.sql",
                "V003__seed_kitchen_printers.sql")) {
            assertTrue(Files.exists(DIR.resolve(name)), name + " bulunmalı");
        }
        try (var files = Files.list(DIR)) {
            long count = files.filter(p -> p.getFileName().toString().startsWith("V")).count();
            assertEquals(4, count, "beklenen migration sayısı: V001-V004");
        }
    }

    @Test
    void createsTheThreeNewTablesAndNothingElse() {
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS restaurant_areas"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS restaurant_table_layout"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS product_units"));

        long creates = statements.stream()
                .filter(s -> s.trim().toUpperCase(Locale.ROOT).startsWith("CREATE TABLE"))
                .count();
        assertEquals(3, creates, "yalnız üç yeni tablo oluşturulmalı");
    }

    @Test
    void diningTablesIsNotTouched() {
        String upper = sql.toUpperCase(Locale.ROOT);
        assertFalse(upper.contains("ALTER TABLE DINING_TABLES"), "dining_tables değiştirilmemeli");
        assertFalse(upper.contains("REFERENCES DINING_TABLES"), "dining_tables'a FK eklenmemeli");
        assertFalse(upper.contains("DROP TABLE"), "destructive ifade olmamalı");
        assertFalse(upper.contains("DELETE FROM"), "veri silinmemeli");
        assertFalse(upper.contains("UPDATE PRODUCTS"), "mevcut ürün verisi değiştirilmemeli");
    }

    @Test
    void areaSeedHasThirteenAreas() {
        String stmt = statementContaining("INSERT INTO restaurant_areas");
        // Satırlar UNION ALL ile zincirlenir: n satır → n-1 UNION ALL
        assertEquals(13, unionAllCount(stmt) + 1, "13 alan seed edilmeli");
        assertTrue(stmt.contains("'3. Bina', 'Bahçe', ''"), "salonsuz bahçe alanı bulunmalı");
        assertTrue(stmt.contains("'1. Bina'") && stmt.contains("'2. Bina'"), "üç bina da bulunmalı");
    }

    @Test
    void tableLayoutSeedHasSeventyTables() {
        String stmt = statementContaining("INSERT INTO restaurant_table_layout");
        assertEquals(70, unionAllCount(stmt) + 1, "70 masa satırı seed edilmeli");

        java.util.List<Integer> numbers = new java.util.ArrayList<>();
        Matcher m = Pattern.compile("SELECT\\s+(\\d{3})\\b").matcher(stmt);
        while (m.find()) {
            numbers.add(Integer.parseInt(m.group(1)));
        }
        assertEquals(70, numbers.size(), "70 masa numarası okunmalı");

        java.util.List<Integer> expected = new java.util.ArrayList<>();
        for (int n = 101; n <= 130; n++) expected.add(n);
        for (int n = 201; n <= 230; n++) expected.add(n);
        for (int n = 301; n <= 310; n++) expected.add(n);
        assertEquals(expected, numbers, "masa aralıkları 101-130, 201-230, 301-310 olmalı");

        assertTrue(stmt.contains("JOIN restaurant_areas"),
                "masalar area_id'yi alan tablosundan çözmeli (hardcoded id yok)");
    }

    @Test
    void productUnitSeedContainsTheSixCodes() {
        String stmt = statementContaining("'porsiyon'");
        for (String code : List.of("porsiyon", "şiş", "adet", "kg", "tabak", "kase")) {
            assertTrue(stmt.contains("'" + code + "'"), "birim eksik: " + code);
        }
        assertEquals(6, unionAllCount(stmt) + 1, "altı standart birim seed edilmeli");
        assertTrue(stmt.contains("NOT EXISTS"), "birim seed'i idempotent olmalı");
    }

    @Test
    void customUnitLabelsAreImportedFromProducts() {
        assertTrue(sql.contains("SELECT DISTINCT TRIM(p.unit_label)"),
                "mevcut özel birimler products'tan aktarılmalı");
        assertTrue(sql.contains("FROM products p"), "products yalnız OKUNMALI");
    }

    @Test
    void everySeedIsIdempotent() {
        long inserts = statements.stream()
                .filter(s -> s.trim().toUpperCase(Locale.ROOT).startsWith("INSERT INTO"))
                .count();
        long guarded = statements.stream()
                .filter(s -> s.trim().toUpperCase(Locale.ROOT).startsWith("INSERT INTO"))
                .filter(s -> s.toUpperCase(Locale.ROOT).contains("NOT EXISTS"))
                .count();
        assertEquals(4, inserts, "dört seed ifadesi beklenir");
        assertEquals(inserts, guarded, "her seed NOT EXISTS korumalı olmalı");
    }

    @Test
    void statementsSplitCleanly() {
        // Splitter ';' üzerinden ayırır; literal içinde ';' olmamalı.
        assertTrue(statements.size() >= 7, "3 CREATE + 4 INSERT beklenir: " + statements.size());
        for (String s : statements) {
            assertFalse(s.isBlank(), "boş ifade olmamalı");
        }
    }

    // ------------------------------------------------------------------

    /**
     * İlgili SQL ifadesinin TAMAMINI döner. Metin aralığı yerine gerçek ifade
     * sınırı kullanılır: 'AS d' gibi bir işaretle kesmek 'AS display_order' /
     * 'AS display_name' ile yanlış eşleşirdi.
     */
    private static String statementContaining(String marker) {
        return statements.stream()
                .filter(s -> s.contains(marker))
                .findFirst()
                .orElseGet(() -> {
                    fail("İçinde '" + marker + "' geçen SQL ifadesi bulunamadı");
                    return "";
                });
    }

    private static int unionAllCount(String statement) {
        return statement.split("UNION ALL", -1).length - 1;
    }
}
