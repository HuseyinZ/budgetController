package UI;

import model.TablePlacement;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kat planı masa butonu: şekil desteği, şekle göre tıklama alanı, dönüş ve
 * JButton davranışının (tıklama, metin, renk) korunması.
 */
class FloorTableButtonTest {

    private static FloorTableButton button(String shape, int rotation, int w, int h) {
        FloorTableButton b = new FloorTableButton();
        b.setSize(w, h);
        b.setShapeGeometry(shape, rotation, w, h);
        return b;
    }

    @Test
    void allSupportedShapesAreRecognisedAndUnknownFallsBackToRect() {
        for (String shape : new String[]{"RECT", "SQUARE", "ROUND", "OVAL"}) {
            assertEquals(shape, button(shape, 0, 100, 100).getShapeName());
        }
        assertEquals("ROUND", button("round", 0, 100, 100).getShapeName(), "büyük/küçük harf duyarsız");
        assertEquals("RECT", button("TRIANGLE", 0, 100, 100).getShapeName(), "bilinmeyen → dikdörtgen");
        assertEquals("RECT", button(null, 0, 100, 100).getShapeName());
        assertEquals("RECT", TablePlacement.normalizeShape(" "));
    }

    @Test
    void rectangularShapesAcceptClicksInTheirCorners() {
        assertTrue(button("RECT", 0, 100, 60).contains(2, 2));
        assertTrue(button("SQUARE", 0, 80, 80).contains(78, 78));
    }

    @Test
    void roundShapesIgnoreClicksOutsideTheEllipse() {
        FloorTableButton round = button("ROUND", 0, 100, 100);
        assertTrue(round.contains(50, 50), "merkez tıklanabilir");
        assertFalse(round.contains(2, 2), "yuvarlak masanın köşesi masa değildir");

        FloorTableButton oval = button("OVAL", 0, 200, 80);
        assertTrue(oval.contains(100, 40));
        assertFalse(oval.contains(5, 5));
    }

    @Test
    void rotationIsAppliedToTheHitArea() {
        // 200x40 çubuk, 200x200 kutuda: düzken yatay, 90° döndürülünce dikey
        FloorTableButton flat = new FloorTableButton();
        flat.setSize(200, 200);
        flat.setShapeGeometry("RECT", 0, 200, 40);
        assertTrue(flat.contains(190, 100), "yatay çubuğun sağ ucu");
        assertFalse(flat.contains(100, 10), "yatay çubuğun üstü boş");

        FloorTableButton rotated = new FloorTableButton();
        rotated.setSize(200, 200);
        rotated.setShapeGeometry("RECT", 90, 200, 40);
        assertTrue(rotated.contains(100, 10), "dikey çubuğun üst ucu");
        assertFalse(rotated.contains(190, 100), "dikey çubuğun yanı boş");
    }

    @Test
    void itIsStillARealButton_clickTextAndColorWork() {
        FloorTableButton b = button("ROUND", 0, 100, 100);
        AtomicInteger clicks = new AtomicInteger();
        b.addActionListener(e -> clicks.incrementAndGet());

        b.doClick();
        assertEquals(1, clicks.get(), "tıklama sipariş diyaloğunu açan dinleyiciyi tetiklemeli");

        // refreshButton'ın yaptığı güncellemeler aynen uygulanır
        b.setText("<html>Masa 101</html>");
        b.setBackground(Color.ORANGE);
        assertEquals("<html>Masa 101</html>", b.getText());
        assertEquals(Color.ORANGE, b.getBackground());
    }

    @Test
    void emptyButtonNeverClaimsClicks() {
        FloorTableButton b = new FloorTableButton();
        assertFalse(b.contains(0, 0), "boyutsuz buton tıklama almamalı");
    }

    @Test
    void fontScalesWithTableSizeWithinReadableBounds() {
        assertEquals(10f, button("RECT", 0, 30, 30).getFont().getSize2D(), 0.01, "alt sınır");
        assertEquals(16f, button("RECT", 0, 300, 300).getFont().getSize2D(), 0.01, "üst sınır");
    }
}
