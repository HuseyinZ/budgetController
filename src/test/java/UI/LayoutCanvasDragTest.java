package UI;

import model.TableLayoutEntry;
import org.junit.jupiter.api.Test;

import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---------------- düzenleme sırasında çakışma engeli ----------------

    private static LayoutCanvas canvasWith(List<TableLayoutEntry> tables) {
        LayoutCanvas canvas = new LayoutCanvas();
        canvas.setSize(1000, 1000);
        canvas.setTables(tables, Set.of());
        return canvas;
    }

    private static TableLayoutEntry at(int no, int x, int y, int w, int h, boolean active) {
        TableLayoutEntry t = table(no, active);
        t.setPosX(x); t.setPosY(y); t.setWidth(w); t.setHeight(h);
        return t;
    }

    @Test
    void activeTableCannotBeDraggedOntoAnotherActiveTable() {
        TableLayoutEntry moving = at(101, 0, 0, 100, 100, true);
        TableLayoutEntry blocker = at(102, 400, 0, 100, 100, true);
        LayoutCanvas canvas = canvasWith(List.of(moving, blocker));

        press(canvas, 50, 50);
        drag(canvas, 480, 50);   // 430,0 → 102 ile çakışır

        assertEquals(0, moving.getPosX(), "çakışan hareket reddedilmeli");
        assertEquals(0, moving.getPosY());
    }

    @Test
    void rejectedDragKeepsTheLastValidPosition() {
        TableLayoutEntry moving = at(101, 0, 0, 100, 100, true);
        TableLayoutEntry blocker = at(102, 400, 0, 100, 100, true);
        LayoutCanvas canvas = canvasWith(List.of(moving, blocker));

        press(canvas, 50, 50);
        drag(canvas, 250, 50);   // geçerli → 200,0
        drag(canvas, 480, 50);   // geçersiz → reddedilir

        assertEquals(200, moving.getPosX(), "son GEÇERLİ konum korunmalı");
        assertEquals(0, moving.getPosY());
    }

    @Test
    void freeMovementStillWorks() {
        TableLayoutEntry moving = at(101, 0, 0, 100, 100, true);
        TableLayoutEntry other = at(102, 800, 800, 100, 100, true);
        LayoutCanvas canvas = canvasWith(List.of(moving, other));

        press(canvas, 50, 50);
        drag(canvas, 350, 250);

        assertEquals(300, moving.getPosX());
        assertEquals(200, moving.getPosY());
    }

    @Test
    void edgeTouchingIsAllowedWhileDragging() {
        TableLayoutEntry moving = at(101, 0, 0, 100, 100, true);
        TableLayoutEntry blocker = at(102, 300, 0, 100, 100, true);
        LayoutCanvas canvas = canvasWith(List.of(moving, blocker));

        press(canvas, 50, 50);
        drag(canvas, 250, 50);   // 200..300 ile 300..400 → yalnız kenar teması

        assertEquals(200, moving.getPosX(), "kenar teması serbest olmalı");
    }

    @Test
    void inactiveTablesDoNotBlockDragging() {
        TableLayoutEntry moving = at(101, 0, 0, 100, 100, true);
        TableLayoutEntry passive = at(102, 400, 0, 100, 100, false);
        LayoutCanvas canvas = canvasWith(List.of(moving, passive));

        press(canvas, 50, 50);
        drag(canvas, 480, 50);

        assertEquals(430, moving.getPosX(), "pasif masa aktif düzeni engellemez");
    }

    @Test
    void overlapProbeMatchesServiceSemantics() {
        TableLayoutEntry moving = at(101, 0, 0, 100, 100, true);
        TableLayoutEntry blocker = at(102, 300, 0, 100, 100, true);
        LayoutCanvas canvas = canvasWith(List.of(moving, blocker));

        assertTrue(canvas.wouldOverlap(moving, 250, 0, 100, 100), "üst üste binme");
        assertFalse(canvas.wouldOverlap(moving, 200, 0, 100, 100), "kenar teması serbest");
        assertFalse(canvas.wouldOverlap(moving, 0, 0, 100, 100), "kendisiyle çakışmaz");
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
