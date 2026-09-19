package UI;

import model.TableLayoutEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Editörün sözleşmeleri: saf yardımcılar doğrudan, Swing'e bağlı davranışlar
 * kaynak düzeyinde doğrulanır (headless CI'da panel örneklenemez).
 */
class LayoutEditorPanelTest {

    private static final Path PANEL = Path.of("src", "main", "java", "UI", "LayoutEditorPanel.java");
    private static final Path CANVAS = Path.of("src", "main", "java", "UI", "LayoutCanvas.java");

    private static String panel;
    private static String canvas;

    @BeforeAll
    static void read() throws IOException {
        panel = Files.readString(PANEL, StandardCharsets.UTF_8);
        canvas = Files.readString(CANVAS, StandardCharsets.UTF_8);
    }

    // ---------------- saf yardımcılar ----------------

    @Test
    void tableNumberInputIsParsedAndValidated() {
        assertEquals(List.of(101, 205, 4020), LayoutEditorPanel.parseTableNumbers(" 101, 205 ,4020 "));
        assertThrows(IllegalArgumentException.class, () -> LayoutEditorPanel.parseTableNumbers(""));
        assertThrows(IllegalArgumentException.class, () -> LayoutEditorPanel.parseTableNumbers("a,1"));
        assertThrows(IllegalArgumentException.class, () -> LayoutEditorPanel.parseTableNumbers("0"));
        assertThrows(IllegalArgumentException.class, () -> LayoutEditorPanel.parseTableNumbers("5,5"));
    }

    @Test
    void workingCopyIsDetachedFromTheManagementView() {
        TableLayoutEntry source = new TableLayoutEntry();
        source.setTableNo(101);
        source.setAreaId(3);
        source.setPosX(10);
        source.setActive(true);

        TableLayoutEntry copy = LayoutEditorPanel.copy(source);
        copy.setPosX(900);
        copy.setActive(false);

        assertEquals(10, source.getPosX(), "kaynak kayıt değişmemeli");
        assertTrue(source.isActive());
        assertEquals(101, copy.getTableNo());
        assertEquals(3, copy.getAreaId());
    }

    // ---------------- davranış sözleşmeleri ----------------

    @Test
    void openingTheEditorNeverWritesToTheDatabase() {
        String body = bodyOf(panel, "private void showSelectedArea()");
        assertTrue(body.contains("LayoutAutoArrange.arrangeMissing("),
                "koordinatsız masalar geçici ızgarayla gösterilmeli");
        for (String write : List.of("updatePlacement", "addTable", "setTableActive", "insert")) {
            assertFalse(body.contains(write), "editörü açmak DB'ye yazmamalı: " + write);
        }
        assertTrue(body.contains("copy(source)"), "çalışma kopyası üzerinde çalışılmalı");
    }

    @Test
    void placementChangesAreSavedOnceInASingleBatch() {
        String body = bodyOf(panel, "private void onSavePlacements()");
        assertTrue(body.contains("dirtyTables.contains"), "yalnız kirli kayıtlar gönderilmeli");
        assertEquals(1, body.split("management\\.updatePlacements\\(", -1).length - 1,
                "tek toplu çağrı (tek transaction) olmalı");
        int overlapCheck = body.indexOf("findOverlap(");
        int save = body.indexOf("SwingWorker");
        assertTrue(overlapCheck >= 0 && overlapCheck < save,
                "çakışma kontrolü kaydetmeden ÖNCE yapılmalı");
    }

    @Test
    void everyDatabaseCallRunsOffTheEventDispatchThread() {
        for (String method : List.of("private void reloadFromDatabase(String successMessage)",
                "private void onSavePlacements()",
                "private void runMutation(String successMessage, Runnable action)")) {
            String body = bodyOf(panel, method);
            assertTrue(body.contains("SwingWorker"), method + " EDT'yi bloklamamalı");
            assertTrue(body.contains("doInBackground"), method + " arka planda çalışmalı");
        }
    }

    @Test
    void successfulMutationRefreshesRuntimeLayoutAndEditor() {
        String body = bodyOf(panel, "private void afterSuccessfulMutation(String successMessage)");
        int reload = body.indexOf("appState.reloadLayout()");
        int refresh = body.indexOf("reloadFromDatabase(");
        assertTrue(reload >= 0, "runtime düzen yenilenmeli");
        assertTrue(refresh > reload, "yönetim görüntüsü de yeniden okunmalı");
    }

    @Test
    void failedReloadIsReportedWithoutPretendingTheWriteWasRolledBack() {
        String body = bodyOf(panel, "private void afterSuccessfulMutation(String successMessage)");
        assertTrue(body.contains("catch (RuntimeException"), "reload hatası ele alınmalı");
        assertTrue(body.contains("KAYDEDİLDİ"),
                "kullanıcıya DB değişikliğinin kaydedildiği söylenmeli");
        assertTrue(body.contains("önceki geçerli"),
                "eski geçerli düzenle devam edildiği belirtilmeli");
        assertFalse(body.contains("geri alındı"), "geri alınmış gibi gösterilmemeli");
    }

    @Test
    void managementReadPathIsAdminAuthorized() {
        String service = readSource(Path.of("src", "main", "java", "service", "layout",
                "RestaurantLayoutManagementService.java"));
        String body = bodyOf(service, "public ManagementView loadForManagement(User user)");
        assertTrue(body.contains("requireAdmin("), "yönetim okuması da yetkili olmalı");
        assertTrue(body.contains("findAllAreasOrdered") && body.contains("findAllTablesOrdered"),
                "pasif kayıtlar da okunmalı");
    }

    @Test
    void canvasKeepsCoordinatesNormalizedAndDoesNotCallServices() {
        assertTrue(canvas.contains("LayoutPlacementRules.MAX_COORD"),
                "tuval 0-1000 normalize uzayı kullanmalı");
        assertTrue(canvas.contains("clamp("), "sürükleme sınır dışına taşmamalı");
        for (String forbidden : List.of("management.", "Db.", "SwingWorker", "updatePlacement")) {
            assertFalse(canvas.contains(forbidden), "tuval DB/servis çağırmamalı: " + forbidden);
        }
    }

    @Test
    void existingTableNumberAndAreaAreNeverEdited() {
        // Yeni masa oluştururken numara verilir; MEVCUT bir masanın numarası ya
        // da alanı hiçbir düzenleme yolunda değiştirilmez.
        String properties = bodyOf(panel, "private void applyPropertyChange()");
        assertFalse(properties.contains("setTableNo("), "masa numarası düzenlenemez");
        assertFalse(properties.contains("setAreaId("), "masa başka alana taşınamaz");

        String save = bodyOf(panel, "private void onSavePlacements()");
        assertFalse(save.contains("setTableNo("), save);
        assertFalse(save.contains("setAreaId("), save);

        assertFalse(canvas.contains("setTableNo("), "tuval numarayı değiştirmemeli");
        assertFalse(canvas.contains("setAreaId("), "tuval alanı değiştirmemeli");
    }

    @Test
    void editorShowsInactiveAreasAndTablesToo() {
        assertTrue(panel.contains("(pasif)"), "pasif kayıtlar işaretlenerek gösterilmeli");
        assertTrue(canvas.contains("INACTIVE_FILL"), "tuval pasif masayı ayırt etmeli");
    }

    // ------------------------------------------------------------------

    private static String readSource(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            fail("Kaynak okunamadı: " + path);
            return "";
        }
    }

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
