package service.layout;

import model.TableLayoutEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Görsel yerleşim doğrulaması — editör yokken bile çizilemez değer girilemez. */
class LayoutPlacementRulesTest {

    private static TableLayoutEntry entry(Integer x, Integer y, Integer w, Integer h,
                                          String shape, int rotation) {
        TableLayoutEntry t = new TableLayoutEntry();
        t.setTableNo(101);
        t.setAreaId(1);
        t.setPosX(x);
        t.setPosY(y);
        t.setWidth(w);
        t.setHeight(h);
        t.setShape(shape);
        t.setRotationDeg(rotation);
        return t;
    }

    @Test
    void unplacedTableIsAllowed() {
        TableLayoutEntry t = entry(null, null, null, null, null, 0);
        LayoutPlacementRules.validateAndNormalize(t);
        assertEquals("RECT", t.getShape(), "şekil varsayılana normalize edilmeli");
        assertNull(t.getPosX());
    }

    @Test
    void shapeIsNormalizedAndRestrictedToSupportedValues() {
        TableLayoutEntry ok = entry(0, 0, 10, 10, "round", 0);
        LayoutPlacementRules.validateAndNormalize(ok);
        assertEquals("ROUND", ok.getShape());

        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> LayoutPlacementRules.validateAndNormalize(entry(0, 0, 10, 10, "TRIANGLE", 0)));
    }

    @Test
    void coordinatesMustStayInsideTheNormalizedSpace() {
        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> LayoutPlacementRules.validateAndNormalize(entry(-1, 0, 10, 10, "RECT", 0)));
        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> LayoutPlacementRules.validateAndNormalize(entry(1001, 0, 10, 10, "RECT", 0)));
        // 0..1000 sınırları dahil
        LayoutPlacementRules.validateAndNormalize(entry(990, 990, 10, 10, "RECT", 0));
    }

    @Test
    void dimensionsMustBePositiveAndFitInsideBounds() {
        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> LayoutPlacementRules.validateAndNormalize(entry(0, 0, 0, 10, "RECT", 0)));
        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> LayoutPlacementRules.validateAndNormalize(entry(0, 0, -5, 10, "RECT", 0)));
        var ex = assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> LayoutPlacementRules.validateAndNormalize(entry(995, 0, 10, 10, "RECT", 0)));
        assertTrue(ex.getMessage().contains("taşıyor"), ex.getMessage());
    }

    @Test
    void rotationMustBeSane() {
        LayoutPlacementRules.validateAndNormalize(entry(0, 0, 10, 10, "RECT", 359));
        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> LayoutPlacementRules.validateAndNormalize(entry(0, 0, 10, 10, "RECT", 360)));
        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> LayoutPlacementRules.validateAndNormalize(entry(0, 0, 10, 10, "RECT", -1)));
    }

    @Test
    void halfSpecifiedPlacementIsRejected() {
        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> LayoutPlacementRules.validateAndNormalize(entry(10, null, 10, 10, "RECT", 0)));
        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> LayoutPlacementRules.validateAndNormalize(entry(10, 10, 10, null, "RECT", 0)));
    }

    @Test
    void tableNumberAndOrderMustBeValid() {
        TableLayoutEntry bad = entry(null, null, null, null, "RECT", 0);
        bad.setTableNo(0);
        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> LayoutPlacementRules.validateAndNormalize(bad));

        TableLayoutEntry negativeOrder = entry(null, null, null, null, "RECT", 0);
        negativeOrder.setDisplayOrder(-1);
        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> LayoutPlacementRules.validateAndNormalize(negativeOrder));
    }
}
