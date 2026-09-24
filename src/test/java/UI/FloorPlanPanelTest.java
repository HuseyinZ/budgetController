package UI;

import model.TablePlacement;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kat planı ölçekleme: görünüm alanının tamamı kullanılır (eksen başına ayrı
 * ölçek), göreli düzen korunur, SQUARE/ROUND oranını korur, masalar okunaklı
 * kalır, gerekirse yalnız ilgili eksende kaydırma çıkar.
 * {@code JPanel} headless ortamda örneklenebilir; veritabanı gerekmez.
 */
class FloorPlanPanelTest {

    private static final int P = FloorPlanPanel.PADDING;

    private static TablePlacement shape(int no, int x, int y, int w, int h, String shape) {
        return new TablePlacement(no, x, y, w, h, shape, 0);
    }

    private static TablePlacement rect(int no, int x, int y, int w, int h) {
        return shape(no, x, y, w, h, "RECT");
    }

    // ---------------- ölçek ----------------

    @Test
    void squareViewportScalesOneToOne() {
        Rectangle r = FloorPlanPanel.toPixels(rect(1, 100, 200, 110, 90), 1000 + 2 * P, 1000 + 2 * P);
        assertEquals(new Rectangle(P + 100, P + 200, 110, 90), r);
    }

    @Test
    void wideScreenUsesTheFullWidthInsteadOfACenteredSquare() {
        int width = 1800 + 2 * P;    // sx = 1.8
        int height = 500 + 2 * P;    // sy = 0.5

        Rectangle leftEdge = FloorPlanPanel.toPixels(rect(1, 0, 0, 110, 90), width, height);
        assertEquals(P, leftEdge.x, "plan sol kenardan başlamalı — ortalanmış kare yok");
        assertEquals(P, leftEdge.y);

        Rectangle rightEdge = FloorPlanPanel.toPixels(rect(2, 890, 910, 110, 90), width, height);
        assertEquals(width - P, rightEdge.x + rightEdge.width, "plan tüm genişliği kullanmalı");
        assertEquals(height - P, rightEdge.y + rightEdge.height, "plan tüm yüksekliği kullanmalı");

        Rectangle r = FloorPlanPanel.toPixels(rect(3, 100, 200, 110, 90), width, height);
        assertEquals(new Rectangle(P + 180, P + 100, 198, 45), r, "eksen başına ayrı ölçek");
    }

    @Test
    void relativeArrangementIsPreservedOnAnyAspectRatio() {
        TablePlacement a = rect(1, 100, 100, 100, 100);
        TablePlacement b = rect(2, 600, 700, 100, 100);
        for (int[] view : new int[][]{{1016, 1016}, {1916, 616}, {716, 1216}}) {
            Rectangle ra = FloorPlanPanel.toPixels(a, view[0], view[1]);
            Rectangle rb = FloorPlanPanel.toPixels(b, view[0], view[1]);
            double sx = FloorPlanPanel.scaleX(view[0]);
            double sy = FloorPlanPanel.scaleY(view[1]);
            assertEquals(500 * sx, rb.getCenterX() - ra.getCenterX(), 1.0, "yatay göreli mesafe");
            assertEquals(600 * sy, rb.getCenterY() - ra.getCenterY(), 1.0, "dikey göreli mesafe");
        }
    }

    // ---------------- şekil ----------------

    @Test
    void squareAndRoundKeepTheirAspectOnWideScreens() {
        int width = 1800 + 2 * P;
        int height = 500 + 2 * P;
        for (String keep : List.of("SQUARE", "ROUND")) {
            TablePlacement p = shape(1, 100, 100, 100, 100, keep);
            Rectangle r = FloorPlanPanel.toPixels(p, width, height);
            assertEquals(r.width, r.height, keep + " kare/daire kalmalı");
            assertEquals(50, r.width, "kenar iki eksenin küçüğü");

            // Kaydedilen kutunun merkezine oturur → göreli düzen bozulmaz
            Rectangle box = FloorPlanPanel.toPixels(shape(1, 100, 100, 100, 100, "RECT"), width, height);
            assertEquals(box.getCenterX(), r.getCenterX(), 1.0);
            assertEquals(box.getCenterY(), r.getCenterY(), 1.0);
        }
    }

    @Test
    void rectAndOvalStretchWithTheViewport() {
        int width = 1800 + 2 * P;
        int height = 500 + 2 * P;
        for (String stretch : List.of("RECT", "OVAL")) {
            Rectangle r = FloorPlanPanel.toPixels(shape(1, 100, 100, 100, 100, stretch), width, height);
            assertEquals(180, r.width, stretch);
            assertEquals(50, r.height, stretch);
            assertNotEquals(r.width, r.height, stretch + " görünümle esneyebilir");
        }
    }

    @Test
    void rotationUsesTheRotatedBoundingBoxAroundTheSameCenter() {
        Rectangle base = new Rectangle(100, 100, 200, 100);

        Rectangle quarter = FloorPlanPanel.rotatedBounds(base, 90);
        assertEquals(100, quarter.width, "90° dönüşte genişlik/yükseklik yer değiştirir");
        assertEquals(200, quarter.height);
        assertEquals(base.getCenterX(), quarter.getCenterX(), 1.0);
        assertEquals(base.getCenterY(), quarter.getCenterY(), 1.0);

        Rectangle diagonal = FloorPlanPanel.rotatedBounds(base, 45);
        assertTrue(diagonal.width > base.width && diagonal.height > base.height,
                "köşeler kırpılmasın diye kutu büyümeli");

        assertEquals(base, FloorPlanPanel.rotatedBounds(base, 0));
        assertEquals(base, FloorPlanPanel.rotatedBounds(base, 360));
    }

    // ---------------- yeniden boyutlandırma ----------------

    @Test
    void resizeImmediatelyRecalculatesTheLayout() {
        FloorPlanPanel panel = new FloorPlanPanel();
        JButton a = new JButton("A");
        JButton b = new JButton("B");
        panel.addTable(a, rect(1, 0, 0, 100, 100));
        panel.addTable(b, rect(2, 500, 500, 200, 100));

        panel.setSize(1000 + 2 * P, 1000 + 2 * P);
        panel.doLayout();
        Rectangle bigA = a.getBounds();
        Rectangle bigB = b.getBounds();

        panel.setSize(500 + 2 * P, 500 + 2 * P);
        panel.doLayout();
        Rectangle smallB = b.getBounds();
        Rectangle smallA = a.getBounds();

        assertEquals(bigB.width / 2, smallB.width, "ölçü orantılı küçülmeli");
        assertEquals(bigB.height / 2, smallB.height);
        assertEquals((bigB.x - bigA.x) / 2, smallB.x - smallA.x, "göreli mesafe korunmalı");
        assertEquals((bigB.y - bigA.y) / 2, smallB.y - smallA.y);
    }

    @Test
    void widthOnlyResizeLeavesVerticalLayoutUntouched() {
        FloorPlanPanel panel = new FloorPlanPanel();
        JButton a = new JButton("A");
        panel.addTable(a, rect(1, 400, 300, 100, 100));

        panel.setSize(1000 + 2 * P, 600 + 2 * P);
        panel.doLayout();
        Rectangle before = a.getBounds();

        panel.setSize(1600 + 2 * P, 600 + 2 * P);
        panel.doLayout();
        Rectangle after = a.getBounds();

        assertEquals(before.y, after.y, "yükseklik değişmediyse dikey konum aynı");
        assertEquals(before.height, after.height);
        assertTrue(after.x > before.x && after.width > before.width, "genişlik hemen yansımalı");
    }

    @Test
    void layoutPassesShapeGeometryToFloorButtons() {
        FloorPlanPanel panel = new FloorPlanPanel();
        FloorTableButton button = new FloorTableButton();
        panel.addTable(button, new TablePlacement(1, 0, 0, 200, 100, "OVAL", 90));

        panel.setSize(1000 + 2 * P, 1000 + 2 * P);
        panel.doLayout();

        assertEquals("OVAL", button.getShapeName());
        assertEquals(90, button.getRotationDeg());
        assertEquals(100, button.getWidth(), "döndürülmüş kutu genişliği");
        assertEquals(200, button.getHeight());
    }

    // ---------------- okunaklılık / kaydırma ----------------

    @Test
    void tablesAreReadableAtTheMinimumPlanSize() {
        List<TablePlacement> tables = List.of(
                rect(1, 20, 20, 110, 90), rect(2, 150, 20, 110, 90), shape(3, 280, 20, 110, 90, "ROUND"));
        Dimension min = FloorPlanPanel.minimumReadableSize(tables);

        for (TablePlacement t : tables) {
            Rectangle r = FloorPlanPanel.toPixels(t, min.width, min.height);
            assertTrue(Math.min(r.width, r.height) >= FloorPlanPanel.MIN_TABLE_PX,
                    "masa " + t.tableNo() + " okunaksız küçük: " + r);
        }
    }

    @Test
    void typicalContentAreaGivesComfortablyLargeTables() {
        // ~1366x768 ekranda yan panel ve başlıklar çıktıktan sonraki alan
        Rectangle r = FloorPlanPanel.toPixels(rect(1, 20, 20, 110, 90), 1000, 560);
        assertTrue(r.width >= 100, "genişlik: " + r.width);
        assertTrue(r.height >= FloorPlanPanel.MIN_TABLE_PX, "yükseklik: " + r.height);
    }

    @Test
    void scrollsOnlyInTheAxisThatIsGenuinelyTooSmall() {
        FloorPlanPanel panel = new FloorPlanPanel();
        panel.addTable(new JButton(), rect(1, 20, 20, 110, 90));
        JScrollPane scroll = new JScrollPane(panel);
        JViewport viewport = scroll.getViewport();
        Dimension min = panel.getPreferredSize();

        viewport.setSize(1200, 700);
        assertTrue(panel.getScrollableTracksViewportWidth(), "geniş alanda plan görünümü doldurmalı");
        assertTrue(panel.getScrollableTracksViewportHeight());

        viewport.setSize(1200, min.height - 1);
        assertTrue(panel.getScrollableTracksViewportWidth(), "genişlik yeterli → yatay kaydırma yok");
        assertFalse(panel.getScrollableTracksViewportHeight(), "yükseklik yetersiz → dikey kaydırma");
    }

    // ---------------- sınır durumları ----------------

    @Test
    void unresolvedPlacementIsRejected() {
        FloorPlanPanel panel = new FloorPlanPanel();
        assertThrows(IllegalArgumentException.class,
                () -> panel.addTable(new JButton(), TablePlacement.unplaced(1)));
    }

    @Test
    void zeroSizedPanelDoesNotBreakLayout() {
        assertEquals(0.0, FloorPlanPanel.scaleX(0));
        assertEquals(0.0, FloorPlanPanel.scaleY(0));
        Rectangle r = FloorPlanPanel.toPixels(rect(1, 10, 10, 10, 10), 0, 0);
        assertTrue(r.width >= 1 && r.height >= 1, "ölçü en az 1 px olmalı");
        assertEquals(new Dimension(FloorPlanPanel.MIN_VIEW_WIDTH, FloorPlanPanel.MIN_VIEW_HEIGHT),
                FloorPlanPanel.minimumReadableSize(List.of()), "masasız plan için taban boyut");
    }
}
