package service.layout;

import model.TablePlacement;
import org.junit.jupiter.api.Test;
import state.AppState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code GET /api/tables} yerleşim alanları: AppState anlık görüntüsünden gelir,
 * konumsuz masalar Swing ile aynı deterministik geçici yerleşimi alır,
 * hiçbir şey kaydedilmez.
 */
class TablePlacementJsonTest {

    private static final Path HELPER =
            Path.of("src", "main", "java", "service", "layout", "TablePlacementJson.java");

    private static AppState.AreaDefinition area(List<Integer> numbers, List<TablePlacement> placements) {
        return new AppState.AreaDefinition("1. Bina", "1. Kat", "1. Salon", numbers, placements);
    }

    @Test
    void savedPlacementFieldsAreExposed() {
        AppState.AreaDefinition a = area(List.of(101),
                List.of(new TablePlacement(101, 120, 340, 150, 90, "OVAL", 30)));

        Map<String, Object> row = new HashMap<>();
        TablePlacementJson.putPlacement(row, TablePlacementJson.resolvedByTableNo(a).get(101));

        assertEquals(120, row.get("posX"));
        assertEquals(340, row.get("posY"));
        assertEquals(150, row.get("width"));
        assertEquals(90, row.get("height"));
        assertEquals("OVAL", row.get("shape"));
        assertEquals(30, row.get("rotationDeg"));
    }

    @Test
    void unplacedTablesUseTheSameFallbackAsSwing() {
        List<TablePlacement> placements = List.of(
                new TablePlacement(101, 20, 20, 130, 160, "RECT", 0),   // ilk geçici yuvayı işgal eder
                TablePlacement.unplaced(102),
                TablePlacement.unplaced(103));
        AppState.AreaDefinition a = area(List.of(101, 102, 103), placements);

        Map<Integer, TablePlacement> resolved = TablePlacementJson.resolvedByTableNo(a);
        List<TablePlacement> swing = LayoutAutoArrange.resolve(a.getPlacements());

        assertEquals(List.of(101, 102, 103), List.copyOf(resolved.keySet()), "masa sırası korunmalı");
        for (TablePlacement expected : swing) {
            assertEquals(expected, resolved.get(expected.tableNo()), "PWA ve Swing aynı yeri göstermeli");
        }
        for (TablePlacement p : resolved.values()) {
            assertTrue(p.isPlaced(), "her masa çizilebilir olmalı");
        }
        TablePlacement saved = resolved.get(101);
        for (int no : List.of(102, 103)) {
            TablePlacement f = resolved.get(no);
            boolean overlaps = f.posX() < saved.posX() + saved.width() && saved.posX() < f.posX() + f.width()
                    && f.posY() < saved.posY() + saved.height() && saved.posY() < f.posY() + f.height();
            assertFalse(overlaps, "geçici yuva kayıtlı masayla çakışmamalı: " + no);
        }
    }

    @Test
    void fallbackIsDeterministicAcrossRequests() {
        AppState.AreaDefinition a = area(List.of(7, 19, 4020), List.of());
        assertEquals(TablePlacementJson.resolvedByTableNo(a), TablePlacementJson.resolvedByTableNo(a));
    }

    @Test
    void resolvingDoesNotChangeTheSnapshot() {
        AppState.AreaDefinition a = area(List.of(1), List.of(TablePlacement.unplaced(1)));
        TablePlacementJson.resolvedByTableNo(a);
        assertFalse(a.getPlacement(1).orElseThrow().isPlaced(),
                "anlık görüntü (ve dolayısıyla DB) değişmemeli");
    }

    @Test
    void missingPlacementAddsNoFields() {
        Map<String, Object> row = new HashMap<>();
        TablePlacementJson.putPlacement(row, null);
        TablePlacementJson.putPlacement(row, TablePlacement.unplaced(5));
        assertTrue(row.isEmpty(), "yerleşim yoksa PWA ızgarada kalır");
    }

    // ---------------- ApiServer sözleşmesi ----------------

    @Test
    void listTablesKeepsPermissionsAndReadsOnlyTheSnapshot() throws IOException {
        String api = Files.readString(Path.of("src", "main", "java", "service", "api", "ApiServer.java"),
                StandardCharsets.UTF_8);
        assertTrue(api.contains("import service.layout.TablePlacementJson;"),
                "ApiServer yardımcıyı yalnız yeni konumdan kullanmalı");

        String body = bodyOf(api, "private void listTables(Context ctx)");
        assertTrue(body.contains("appState.getAccessibleAreas(user)"), "yetki filtresi değişmemeli");
        assertTrue(body.contains("TablePlacementJson.resolvedByTableNo(area)"));
        assertTrue(body.contains("TablePlacementJson.putPlacement(t, placements.get(tableNo))"));
        for (String kept : List.of("\"tableNo\"", "\"building\"", "\"floor\"", "\"salon\"", "\"status\"", "\"total\"")) {
            assertTrue(body.contains(kept), "mevcut alan korunmalı: " + kept);
        }
        for (String forbidden : List.of("Db.", "Jdbc", "RestaurantLayout", "updatePlacement", "setTableActive")) {
            assertFalse(body.contains(forbidden), "listTables DB'ye/yazmaya dokunmamalı: " + forbidden);
        }

        String helper = Files.readString(HELPER, StandardCharsets.UTF_8);
        for (String forbidden : List.of("DataConnection", "Db.", "Jdbc", "dao.")) {
            assertFalse(helper.contains(forbidden), "yardımcı DB'ye erişmemeli: " + forbidden);
        }
    }

    @Test
    void helperNoLongerLivesInTheProtectedApiPackage() {
        assertFalse(Files.exists(Path.of("src", "main", "java", "service", "api", "TablePlacementJson.java")),
                "service/api TIER-1 korumalı; yardımcı orada kalmamalı");
        assertTrue(Files.exists(HELPER));
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
