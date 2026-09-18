package UI;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regresyon koruması: rezervasyon masa seçici, masa numaralarını
 * yapılandırılmış layout'tan almalı.
 *
 * <p>Açılış artık eksik {@code dining_tables} satırlarını oluşturmuyor
 * (read-only startup). Picker kaynağını DB'den alırsa sıfır kurulumda boş
 * kalır. Bu test o regresyonu kod düzeyinde kilitler; picker'ın kendisi modal
 * bir Swing dialogu olduğu için headless CI'da çalıştırılamaz.
 */
class ReservationsPanelTablePickerTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "UI", "ReservationsPanel.java");

    private static String pickerBody;

    @BeforeAll
    static void readPickerBody() throws IOException {
        assertTrue(Files.exists(SOURCE), "kaynak bulunamadı: " + SOURCE.toAbsolutePath());
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        pickerBody = bodyOf(source, "private Integer openTablePicker(Window owner)");
    }

    @Test
    void tableNumbersComeFromConfiguredLayout() {
        assertTrue(pickerBody.contains("getAreas()"),
                "masa numaraları AppState layout'undan gelmeli");
        assertTrue(pickerBody.contains("getTableNumbers()"),
                "AreaDefinition.getTableNumbers() kullanılmalı");
    }

    @Test
    void databaseRowsAreOnlyAnOccupancyHint() {
        // getAllTables hâlâ okunabilir, ama artık listenin KAYNAĞI değil.
        int atAreas = pickerBody.indexOf("getAreas()");
        int atTables = pickerBody.indexOf("getAllTables()");
        assertTrue(atAreas >= 0, "layout okuması bulunamadı");
        if (atTables >= 0) {
            assertTrue(atAreas < atTables, "önce layout, sonra yalnız ipucu okunmalı");
            assertTrue(pickerBody.contains("isOccupied()"),
                    "DB okuması yalnız dolu/boş ipucu için kullanılmalı");
        }
    }

    @Test
    void missingDatabaseRowIsTreatedAsEmpty() {
        assertTrue(pickerBody.contains("Boolean.TRUE.equals("),
                "kaydı olmayan masa boş kabul edilmeli (null → boş)");
    }

    @Test
    void pickerNeverCreatesTables() {
        assertFalse(pickerBody.contains("createTable("), "picker masa oluşturmamalı");
        assertFalse(pickerBody.contains("ensureTableExists("), "picker mutasyon yoluna sapmamalı");
        for (String write : new String[]{"INSERT", "UPDATE ", "DELETE"}) {
            assertFalse(pickerBody.toUpperCase(java.util.Locale.ROOT).contains(write),
                    "picker içinde yazma ifadesi olmamalı: " + write);
        }
    }

    // ------------------------------------------------------------------

    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        if (at < 0) {
            fail("Metot bulunamadı: " + signature);
        }
        int open = source.indexOf('{', at + signature.length());
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        fail("Gövde kapanışı bulunamadı: " + signature);
        return "";
    }
}
