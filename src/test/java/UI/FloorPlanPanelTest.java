package UI;

import model.TablePlacement;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kat planı ölçekleme: 0-1000 normalize → piksel, tek tip ölçek, ortalama,
 * yeniden boyutlandırmada göreli düzenin korunması ve dönüş sınır kutusu.
 * {@code JPanel} headless ortamda örneklenebilir; veritabanı gerekmez.
 */
class FloorPlanPanelTest {

    private static final int P = FloorPlanPanel.PADDING;

    private static TablePlacement rect(int no, int x, int y, int w, int h) {
        return new TablePlacement(no, x, y, w, h, "RECT", 0);
    }

    @Test
    void normalizedCoordinatesScaleUniformlyToPixels() {
        // 1024x1024 → kullanılabilir 1000 px → ölçek 1.0, orijin = PADDING
        Rectangle r = FloorPlanPanel.toPixels(rect(1, 100, 200, 110, 90), 1000 + 2 * P, 1000 + 2 * P);
        assertEquals(new Rectangle(P + 100, P + 200, 110, 90), r);

        // Yarım boyut → her şey yarıya
        Rectangle half = FloorPlanPanel.toPixels(rect(1, 100, 200, 110, 90), 500 + 2 * P, 500 + 2 * P);
        assertEquals(new Rectangle(P + 50, P + 100, 55, 45), half);
    }

    @Test
    void nonSquareViewportKeepsAspectRatioAndCentersThePlan() {
        // Geniş görünüm: ölçek yüksekliğe göre, plan yatayda ortalanır
        int width = 1600;
        int height = 500 + 2 * P;
        Rectangle square = FloorPlanPanel.toPixels(rect(1, 0, 0, 100, 100), width, height);

        assertEquals(square.width, square.height, "kare masa kare kalmalı (tek tip ölçek)");
        assertEquals(50, square.width);
        int side = 500;
        assertEquals((width - side) / 2, square.x, "plan yatayda ortalanmalı");
    }

    @Test
    void resizePreservesRelativeLayout() {
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
        Rectangle smallA = a.getBounds();
        Rectangle smallB = b.getBounds();

        assertEquals(bigB.width / 2, smallB.width, "ölçü orantılı küçülmeli");
        assertEquals(bigB.height / 2, smallB.height);
        assertEquals((bigB.x - bigA.x) / 2, smallB.x - smallA.x, "göreli mesafe korunmalı");
        assertEquals((bigB.y - bigA.y) / 2, smallB.y - smallA.y);
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

    @Test
    void unresolvedPlacementIsRejected() {
        FloorPlanPanel panel = new FloorPlanPanel();
        assertThrows(IllegalArgumentException.class,
                () -> panel.addTable(new JButton(), TablePlacement.unplaced(1)));
    }

    @Test
    void planFillsLargeViewportsAndScrollsBelowMinimum() {
        FloorPlanPanel panel = new FloorPlanPanel();
        JScrollPane scroll = new JScrollPane(panel);
        JViewport viewport = scroll.getViewport();

        viewport.setSize(900, 700);
        assertTrue(panel.getScrollableTracksViewportWidth(), "büyük görünümde ölçeklenmeli");
        assertTrue(panel.getScrollableTracksViewportHeight());

        viewport.setSize(300, 300);
        assertFalse(panel.getScrollableTracksViewportWidth(), "çok küçükse kaydırma");
        assertFalse(panel.getScrollableTracksViewportHeight());
    }

    @Test
    void zeroSizedPanelDoesNotBreakLayout() {
        assertEquals(0.0, FloorPlanPanel.scaleFor(0, 0));
        Rectangle r = FloorPlanPanel.toPixels(rect(1, 10, 10, 10, 10), 0, 0);
        assertTrue(r.width >= 1 && r.height >= 1, "ölçü en az 1 px olmalı");
    }
}
