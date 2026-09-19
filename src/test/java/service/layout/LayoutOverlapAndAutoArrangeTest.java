package service.layout;

import model.TableLayoutEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Çakışma tespiti ve geçici ızgara yerleşimi — ikisi de saf, DB'ye dokunmaz. */
class LayoutOverlapAndAutoArrangeTest {

    private static TableLayoutEntry placed(int no, Integer x, Integer y, Integer w, Integer h) {
        TableLayoutEntry t = new TableLayoutEntry();
        t.setTableNo(no);
        t.setPosX(x); t.setPosY(y); t.setWidth(w); t.setHeight(h);
        t.setShape("RECT");
        return t;
    }

    @Test
    void overlappingTablesAreDetected() {
        var overlap = LayoutPlacementRules.findOverlap(List.of(
                placed(101, 0, 0, 100, 100),
                placed(102, 50, 50, 100, 100)));
        assertTrue(overlap.isPresent());
        assertTrue(overlap.get().contains("101") && overlap.get().contains("102"), overlap.get());
    }

    @Test
    void touchingEdgesIsNotAnOverlap() {
        assertTrue(LayoutPlacementRules.findOverlap(List.of(
                placed(101, 0, 0, 100, 100),
                placed(102, 100, 0, 100, 100))).isEmpty(), "yan yana masa serbest");
    }

    @Test
    void unplacedTablesAreIgnoredByOverlapCheck() {
        assertTrue(LayoutPlacementRules.findOverlap(List.of(
                placed(101, 0, 0, 100, 100),
                placed(102, null, null, null, null))).isEmpty());
    }

    @Test
    void autoArrangeOnlyFillsMissingPlacementsAndStaysInBounds() {
        List<TableLayoutEntry> tables = new ArrayList<>(List.of(
                placed(101, 500, 500, 80, 60),
                placed(102, null, null, null, null),
                placed(103, null, null, null, null)));

        LayoutAutoArrange.arrangeMissing(tables);

        assertEquals(500, tables.get(0).getPosX(), "konumlu masa korunmalı");
        assertEquals(80, tables.get(0).getWidth());
        for (TableLayoutEntry t : tables) {
            assertNotNull(t.getPosX());
            LayoutPlacementRules.validateAndNormalize(t);   // sınırlar içinde olmalı
        }
        assertFalse(LayoutPlacementRules.findOverlap(tables.subList(1, 3)).isPresent(),
                "ızgara kendi içinde çakışmamalı");
    }

    @Test
    void autoArrangeIsPureAndDoesNotTouchActiveFlagOrNumbers() {
        TableLayoutEntry t = placed(4020, null, null, null, null);
        t.setActive(false);
        LayoutAutoArrange.arrangeMissing(new ArrayList<>(List.of(t)));

        assertEquals(4020, t.getTableNo(), "masa numarası değişmemeli");
        assertFalse(t.isActive(), "aktiflik değişmemeli");
    }

    @Test
    void emptyInputIsSafe() {
        LayoutAutoArrange.arrangeMissing(null);
        LayoutAutoArrange.arrangeMissing(new ArrayList<>());
        assertTrue(LayoutPlacementRules.findOverlap(List.of()).isEmpty());
        assertNull(null);
    }
}
