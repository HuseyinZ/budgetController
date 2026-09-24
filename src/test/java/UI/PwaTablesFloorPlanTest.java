package UI;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PWA "Masalar" ekranının fiziksel plan sözleşmeleri.
 *
 * <p>Projede JS test çalıştırıcısı olmadığı için {@code app.js},
 * {@code index.html} ve {@code style.css} kaynak düzeyinde doğrulanır. Ölçek
 * sabitleri, birim testli Swing {@link FloorPlanPanel} / {@link FloorTableButton}
 * ile karşılaştırılır: iki ekran aynı geometriyi kullanmalı.
 */
class PwaTablesFloorPlanTest {

    private static final Path WEBAPP = Path.of("src", "main", "resources", "webapp");
    private static String js;
    private static String html;
    private static String css;

    @BeforeAll
    static void read() throws IOException {
        js = Files.readString(WEBAPP.resolve("app.js"), StandardCharsets.UTF_8);
        html = Files.readString(WEBAPP.resolve("index.html"), StandardCharsets.UTF_8);
        css = Files.readString(WEBAPP.resolve("style.css"), StandardCharsets.UTF_8);
    }

    // ---------------- filtreler ----------------

    @Test
    void salonFilterExistsNextToBuildingAndFloor() {
        int building = html.indexOf("id=\"buildingFilter\"");
        int floor = html.indexOf("id=\"floorFilter\"");
        int salon = html.indexOf("id=\"salonFilter\"");
        assertTrue(building >= 0 && floor > building && salon > floor, "Bina → Kat → Salon sırası");
    }

    @Test
    void changingBuildingOrFloorResetsTheLowerLevels() {
        String populate = fn("populateFilters");
        String building = between(populate, "bldSel.onchange", "floorSel.onchange");
        assertTrue(building.contains("refreshFloorOptions('')") && building.contains("refreshSalonOptions('')"),
                "bina değişince kat ve salon sıfırlanmalı");
        String floor = between(populate, "floorSel.onchange", "salonSel.onchange");
        assertTrue(floor.contains("refreshSalonOptions('')"), "kat değişince salon sıfırlanmalı");
        assertFalse(floor.contains("refreshFloorOptions"), "kat değişince kat listesi korunur");
    }

    @Test
    void filterListenersDoNotAccumulateAcrossVisits() {
        String populate = fn("populateFilters");
        assertFalse(populate.contains("addEventListener"),
                "goToTables her çağrıldığında dinleyici birikmemeli (onchange ataması)");
        // Üç seçicinin de tek dinleyicisi ATAMA ile bağlanmalı (idempotent)
        assertTrue(populate.contains("bldSel.onchange = "), "bina seçici atama ile bağlanmalı");
        assertTrue(populate.contains("floorSel.onchange = "), "kat seçici atama ile bağlanmalı");
        assertTrue(populate.contains("salonSel.onchange = renderTables"), "salon seçici atama ile bağlanmalı");

        // Seçiciler başka bir yerde de dinleyici eklenerek bağlanmamalı
        for (String id : List.of("buildingFilter", "floorFilter", "salonFilter")) {
            Matcher m = Pattern.compile("getElementById\\('" + id + "'\\)\\s*\\.addEventListener").matcher(js);
            assertFalse(m.find(), id + " için birikebilen dinleyici bağlanmamalı");
        }
    }

    @Test
    void salonNeedsBuildingAndFloorAndEmptySalonNamesStayDistinct() {
        String salons = fn("refreshSalonOptions");
        assertTrue(salons.contains("if (!b || !f)") && salons.contains("salonSel.disabled = true"),
                "bina/kat seçilmeden salon seçilemez");
        assertTrue(fn("salonOptionValue").contains("'=' +"),
                "adı boş salon 'Tüm Salonlar' ile karışmamalı");
        assertTrue(fn("renderTables").contains("salonOptionValue(t.salon) === s"));
    }

    @Test
    void optionLabelsAreEscaped() {
        assertTrue(fn("optionHtml").contains("escapeHtml(value)") && fn("optionHtml").contains("escapeHtml(label)"));
    }

    // ---------------- görünüm seçimi ----------------

    @Test
    void floorPlanOnlyForACompleteSalonWithTheAllFilter() {
        String render = fn("renderTables");
        assertTrue(render.contains("!!(b && f && s) && App.statusFilter === 'all'"),
                "fiziksel plan: tam salon + 'Tümü'");
        assertTrue(render.contains("filtered.every(hasPlacement)"), "yerleşimi eksikse ızgara");
        int plan = render.indexOf("renderFloorPlan(grid, filtered)");
        int grid = render.indexOf("renderTableGrid(grid, filtered)");
        assertTrue(plan >= 0 && grid > plan, "aksi halde mevcut ızgara");
    }

    @Test
    void bothViewsShareTableContentAndClickBehavior() {
        String inner = fn("tableInnerHtml");
        assertTrue(inner.contains("table-no") && inner.contains("table-total")
                && inner.contains("table-resv-badge"), "numara, toplam ve rezervasyon rozeti korunmalı");
        assertTrue(fn("renderTableGrid").contains("tableInnerHtml(t)"));
        assertTrue(fn("renderFloorPlan").contains("tableInnerHtml(t)"));
        assertTrue(fn("renderTableGrid").contains("tableStatusClass(t)"), "durum renkleri korunmalı");
        assertTrue(fn("renderFloorPlan").contains("tableStatusClass(t)"));
        assertTrue(fn("renderTables").contains("openTable(Number(btn.dataset.table))"),
                "tıklama davranışı her iki görünümde aynı");
    }

    // ---------------- geometri ----------------

    @Test
    void geometryMatchesTheSwingFloorPlan() {
        assertEquals(FloorPlanPanel.SPACE, jsConst("SPACE"), "normalize uzay");
        assertEquals(FloorPlanPanel.PADDING, jsConst("PAD"), "kenar boşluğu");

        String rect = fn("floorPlanRect");
        assertTrue(rect.contains("W - 2 * FLOOR_PLAN.PAD) / FLOOR_PLAN.SPACE")
                && rect.contains("H - 2 * FLOOR_PLAN.PAD) / FLOOR_PLAN.SPACE"),
                "X ve Y ayrı ölçeklenmeli (ortalanmış kare yok)");
        assertTrue(rect.contains("shape === 'SQUARE' || shape === 'ROUND'")
                && rect.contains("Math.min(w, h)"), "SQUARE kare, ROUND daire kalmalı");

        String font = fn("floorPlanFontPx");
        assertTrue(font.contains("Math.max(" + (int) FloorTableButton.MIN_FONT_PT)
                && font.contains("Math.min(" + (int) FloorTableButton.MAX_FONT_PT)
                && font.contains("w / 7") && font.contains("h / 3.2"), "Swing ile aynı yazı kuralı");
    }

    @Test
    void rotationIsVisualOnlyAndTextStaysUpright() {
        String plan = fn("renderFloorPlan");
        assertTrue(plan.contains("floor-table-shape") && plan.contains("transform:rotate(${rot}deg)"),
                "dönüş şekil katmanına uygulanmalı");
        int shape = plan.indexOf("floor-table-shape");
        int rotate = plan.indexOf("transform:rotate");
        int inner = plan.indexOf("tableInnerHtml(t)");
        assertTrue(rotate > shape && inner > rotate, "yazı dönen katmanın dışında");
        assertFalse(fn("layoutFloorPlan").contains("rotate"), "buton ve yazı döndürülmemeli");
    }

    @Test
    void layoutIsResponsiveAndKeepsTablesReadable() {
        String layout = fn("layoutFloorPlan");
        assertTrue(layout.contains("host.clientWidth") && layout.contains("window.innerHeight"),
                "mevcut genişlik ve yüksekliği doldurmalı");
        assertTrue(layout.contains("floorPlanMinSize(tables)"), "okunaklı en küçük boyut korunmalı");
        assertTrue(fn("ensureFloorPlanObserver").contains("ResizeObserver")
                && fn("ensureFloorPlanObserver").contains("'resize'"), "yeniden boyutlandırmada anında");
        assertTrue(css.contains(".view.floor-mode { max-width: none; }"),
                "geniş ekranda gereksiz yan boşluk olmamalı");
        assertTrue(css.contains(".floor-table.shape-ROUND .floor-table-shape")
                && css.contains("border-radius: 50%"), "ROUND/OVAL yuvarlak çizilmeli");
    }

    @Test
    void availableHeightUsesViewportCoordinatesOnly() {
        String layout = fn("layoutFloorPlan");
        assertTrue(layout.contains(
                        "Math.max(0, Math.floor(window.innerHeight - host.getBoundingClientRect().top - 12))"),
                "görünür yükseklik: innerHeight - host'un viewport'a göre üstü, negatif olamaz");
        assertTrue(layout.contains("Math.max(availableHeight, min.height)"),
                "okunaklı en küçük yükseklik korunmalı");
    }

    @Test
    void availableHeightIsIndependentOfScrollPosition() {
        // scrollY ne kadar büyük olursa olsun hesaba girmemeli: viewport
        // yüksekliği ile belge koordinatı karıştırılmaz.
        String layout = fn("layoutFloorPlan");
        for (String scrollSource : List.of("scrollY", "pageYOffset", "scrollTop", "scrollingElement")) {
            assertFalse(layout.contains(scrollSource),
                    "görünür yükseklik kaydırma ofsetinden (" + scrollSource + ") etkilenmemeli");
        }
        // Hesaplama kaydırmaya bağlı bir ara değişkene de dayanmamalı
        assertFalse(layout.contains("pageTop"), "belge koordinatlı ara değer kalmamalı");
    }

    @Test
    void floorPlanRenderingNeverCallsTheApi() {
        for (String name : List.of("renderFloorPlan", "layoutFloorPlan", "floorPlanRect",
                "floorPlanMinSize", "renderTableGrid", "renderTables", "populateFilters")) {
            assertFalse(fn(name).contains("api("), name + " yeni istek/yazma yapmamalı");
        }
    }

    // ------------------------------------------------------------------

    private static int jsConst(String key) {
        Matcher m = Pattern.compile("const FLOOR_PLAN = \\{[^}]*\\b" + key + ":\\s*(\\d+)").matcher(js);
        if (!m.find()) {
            fail("FLOOR_PLAN." + key + " bulunamadı");
        }
        return Integer.parseInt(m.group(1));
    }

    private static String between(String s, String from, String to) {
        int a = s.indexOf(from);
        int b = s.indexOf(to, a + 1);
        if (a < 0 || b < 0) {
            fail("Bölüm bulunamadı: " + from + " … " + to);
        }
        return s.substring(a, b);
    }

    /** {@code function name(...) { ... }} gövdesi. */
    private static String fn(String name) {
        int at = js.indexOf("function " + name + "(");
        if (at < 0) {
            fail("Fonksiyon bulunamadı: " + name);
        }
        int open = js.indexOf('{', js.indexOf(')', at));
        int depth = 0;
        for (int i = open; i < js.length(); i++) {
            char c = js.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return js.substring(open, i + 1);
                }
            }
        }
        fail("Gövde kapanışı bulunamadı: " + name);
        return "";
    }
}
