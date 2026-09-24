package UI;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * "Katlar" paneli sözleşmeleri. Panel {@code AppState} tekiline (dolayısıyla
 * çalışan bir veritabanına) bağlı olduğu için kaynak düzeyinde doğrulanır;
 * çizim/ölçek/şekil davranışının kendisi {@link FloorPlanPanelTest} ve
 * {@link FloorTableButtonTest} içinde gerçek bileşenlerle test edilir.
 */
class RestaurantTablesPanelFloorPlanTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "UI", "RestaurantTablesPanel.java");
    private static String source;

    @BeforeAll
    static void read() throws IOException {
        source = Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    @Test
    void allFilterRendersTheSavedFloorPlanAndFilteredViewsStayGrid() {
        String refresh = bodyOf("private void refreshGrid()");
        int all = refresh.indexOf("activeFilter == StatusFilter.ALL");
        int plan = refresh.indexOf("buildFloorPlan(currentArea)");
        int filtered = refresh.indexOf("buildGlobalFilteredView()");
        assertTrue(all >= 0 && plan > all, "Tümü → kat planı");
        assertTrue(filtered > plan, "Boş/Dolu → ızgara görünümü korunur");
        assertFalse(source.contains("buildTablesGrid("), "sabit 5 sütunlu alan ızgarası kaldırılmalı");
    }

    @Test
    void floorPlanUsesSnapshotPlacementsWithDeterministicFallback() {
        String plan = bodyOf("private JComponent buildFloorPlan(AppState.AreaDefinition area)");
        assertTrue(plan.contains("LayoutAutoArrange.resolve(area.getPlacements())"),
                "yerleşim AppState anlık görüntüsünden okunmalı, konumsuzlar geçici yuva almalı");
        assertTrue(plan.contains("new FloorPlanPanel()"), "normalize kat planı kullanılmalı");
        assertFalse(plan.contains("GridLayout"), "seçili alan sabit ızgara kullanmamalı");
    }

    @Test
    void panelNeverTouchesTheDatabaseDirectly() {
        for (String forbidden : List.of("DataConnection", "Db.", "Jdbc", "dao.",
                "RestaurantLayoutService", "RestaurantLayoutManagementService",
                "updatePlacement", "setTableActive", "createTable(")) {
            assertFalse(source.contains(forbidden),
                    "Katlar paneli DB'ye/servise doğrudan erişmemeli: " + forbidden);
        }
    }

    @Test
    void clickAndStatusBehaviorIsSharedByBothViews() {
        String register = bodyOf("private JButton registerTableButton(JButton button, int tableNo)");
        assertTrue(register.contains("openTableDialog(tableNo)"), "tıklama sipariş diyaloğunu açmalı");
        assertTrue(register.contains("tableButtons.put(tableNo, button)"), "durum güncellemesi için kayıt");
        assertTrue(register.contains("refreshButton(tableNo)"), "numara/toplam/renk ilk çizimde gelmeli");

        assertTrue(bodyOf("private JButton createFloorTableButton(int tableNo)")
                .contains("registerTableButton(new FloorTableButton(), tableNo)"));
        assertTrue(bodyOf("private JButton createTableButton(int tableNo)")
                .contains("registerTableButton(button, tableNo)"));
    }

    @Test
    void reloadEventRebuildsFromTheNewSnapshotAndKeepsTheSelectedSalon() {
        String handler = bodyOf("private void handleStateChange(PropertyChangeEvent event)");
        int nullCheck = handler.indexOf("newValue == null");
        assertTrue(nullCheck >= 0 && handler.indexOf("buildLayout", nullCheck) > nullCheck,
                "reloadLayout sonrası null EVENT_TABLES tam yeniden kurulum yapmalı");

        String build = bodyOf("private void buildLayout()");
        assertTrue(build.contains("appState.getAccessibleAreas(currentUser)"),
                "yeni anlık görüntü ve yetki filtresi kullanılmalı");
        assertTrue(build.contains("previousSalonKey"), "seçili salon korunmalı");
        int remember = build.indexOf("String previousSalonKey = currentSalonKey;");
        int reset = build.indexOf("currentSalonKey = null;");
        assertTrue(remember >= 0 && remember < reset, "önce hatırla, sonra sıfırla");
    }

    @Test
    void permissionsStillFilterVisibleAreas() {
        String build = bodyOf("private void buildLayout()");
        assertTrue(build.contains("getAccessibleAreas(currentUser)"));
        assertTrue(build.contains("Role.GARSON"), "yetkisiz garson uyarısı korunmalı");
    }

    // ------------------------------------------------------------------

    private static String bodyOf(String signature) {
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
