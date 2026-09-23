package UI;

import model.TableLayoutEntry;
import org.junit.jupiter.api.Test;

import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tuval davranışı: PASİF masa sürüklenemez.
 *
 * <p>Pasif masanın konumu aktif düzen çakışma doğrulamasına girmediği için,
 * sürüklenebilmesi sonradan aktifleştirildiğinde üst üste binen iki aktif masa
 * üretebilirdi. {@code JPanel} headless ortamda örneklenebildiğinden bu test
 * gerçek olay gönderimiyle çalışır.
 */
class LayoutCanvasDragTest {

    private static TableLayoutEntry table(int no, boolean active) {
        TableLayoutEntry t = new TableLayoutEntry();
        t.setTableNo(no);
        t.setPosX(0);
        t.setPosY(0);
        t.setWidth(100);
        t.setHeight(100);
        t.setShape("RECT");
        t.setActive(active);
        return t;
    }

    /** 1000x1000 piksel → 0-1000 normalize uzayla birebir ölçek. */
    private static LayoutCanvas canvasWith(TableLayoutEntry t) {
        LayoutCanvas canvas = new LayoutCanvas();
        canvas.setSize(1000, 1000);
        canvas.setTables(List.of(t), Set.of());
        return canvas;
    }

    private static void press(LayoutCanvas canvas, int x, int y) {
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, x, y, 1, false));
    }

    private static void drag(LayoutCanvas canvas, int x, int y) {
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(), 0, x, y, 0, false));
    }

    @Test
    void inactiveTableCannotBeDragged() {
        TableLayoutEntry inactive = table(101, false);
        LayoutCanvas canvas = canvasWith(inactive);

        press(canvas, 50, 50);
        drag(canvas, 600, 600);

        assertEquals(0, inactive.getPosX(), "pasif masanın konumu değişmemeli");
        assertEquals(0, inactive.getPosY(), "pasif masanın konumu değişmemeli");
    }

    @Test
    void activeTableCanStillBeDragged() {
        TableLayoutEntry active = table(101, true);
        LayoutCanvas canvas = canvasWith(active);

        press(canvas, 50, 50);
        drag(canvas, 600, 600);

        assertNotEquals(0, active.getPosX(), "aktif masa sürüklenebilmeli");
        assertEquals(550, active.getPosX(), "tutma noktası korunmalı");
        assertEquals(550, active.getPosY());
    }

    @Test
    void draggingStaysInsideTheNormalizedSpace() {
        TableLayoutEntry active = table(101, true);
        LayoutCanvas canvas = canvasWith(active);

        press(canvas, 50, 50);
        drag(canvas, 5000, 5000);

        assertEquals(900, active.getPosX(), "0-1000 uzayının dışına taşmamalı");
        assertEquals(900, active.getPosY());
    }

    @Test
    void inactiveTableIsStillSelectable() {
        TableLayoutEntry inactive = table(101, false);
        LayoutCanvas canvas = canvasWith(inactive);
        int[] selected = { -1 };
        canvas.setSelectionListener(no -> selected[0] = no);

        press(canvas, 50, 50);

        assertEquals(101, selected[0], "pasif masa seçilebilmeli (özellikleri görünür)");
    }
}
