package service.layout;

import model.TableLayoutEntry;
import model.TablePlacement;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Konumsuz masalar için geçici yerleşim: deterministik, sınırlar içinde ve
 * kaydedilmiş masalarla ÇAKIŞMAZ. Hiçbir şey kaydedilmez (saf fonksiyon).
 */
class LayoutAutoArrangeResolveTest {

    private static boolean overlap(TablePlacement a, TablePlacement b) {
        return a.posX() < b.posX() + b.width() && b.posX() < a.posX() + a.width()
                && a.posY() < b.posY() + b.height() && b.posY() < a.posY() + a.height();
    }

    private static void assertNoOverlap(List<TablePlacement> placements) {
        for (int i = 0; i < placements.size(); i++) {
            for (int j = i + 1; j < placements.size(); j++) {
                assertFalse(overlap(placements.get(i), placements.get(j)),
                        placements.get(i).tableNo() + " ile " + placements.get(j).tableNo() + " çakışıyor");
            }
        }
    }

    @Test
    void allUnplacedTablesGetDeterministicInBoundsSlots() {
        List<TablePlacement> input = List.of(
                TablePlacement.unplaced(101), TablePlacement.unplaced(102), TablePlacement.unplaced(103));

        List<TablePlacement> first = LayoutAutoArrange.resolve(input);
        List<TablePlacement> second = LayoutAutoArrange.resolve(input);

        assertEquals(first, second, "aynı girdi aynı çıktıyı üretmeli");
        assertEquals(List.of(101, 102, 103), first.stream().map(TablePlacement::tableNo).toList());
        for (TablePlacement p : first) {
            assertTrue(p.isPlaced());
            assertTrue(p.posX() >= 0 && p.posX() + p.width() <= 1000);
            assertTrue(p.posY() >= 0 && p.posY() + p.height() <= 1000);
        }
        assertNoOverlap(first);
        // Satır satır, soldan sağa
        assertEquals(20, first.get(0).posX());
        assertEquals(20, first.get(0).posY());
        assertTrue(first.get(1).posX() > first.get(0).posX());
    }

    @Test
    void placedTablesAreReturnedUnchanged() {
        TablePlacement saved = new TablePlacement(101, 500, 500, 80, 60, "OVAL", 30);
        List<TablePlacement> out = LayoutAutoArrange.resolve(List.of(saved, TablePlacement.unplaced(102)));

        assertSame(saved, out.get(0), "kaydedilmiş yerleşime dokunulmamalı");
    }

    @Test
    void fallbackNeverOverlapsSavedTables() {
        // Kayıtlı masa TAM OLARAK ilk geçici yuvayı (20,20) işgal ediyor
        TablePlacement blocker = new TablePlacement(101, 20, 20, 110, 90, "RECT", 0);
        TablePlacement bigBlocker = new TablePlacement(102, 140, 0, 300, 250, "RECT", 0);
        List<TablePlacement> input = new ArrayList<>(List.of(blocker, bigBlocker));
        for (int n = 1; n <= 12; n++) {
            input.add(TablePlacement.unplaced(200 + n));
        }

        List<TablePlacement> out = LayoutAutoArrange.resolve(input);

        assertEquals(input.size(), out.size());
        assertNoOverlap(out);
    }

    @Test
    void mixedLayoutStaysDeterministicRegardlessOfRepeatedCalls() {
        List<TablePlacement> input = List.of(
                TablePlacement.unplaced(7),
                new TablePlacement(19, 150, 20, 110, 90, "RECT", 0),
                TablePlacement.unplaced(4020));

        assertEquals(LayoutAutoArrange.resolve(input), LayoutAutoArrange.resolve(input));
        assertNoOverlap(LayoutAutoArrange.resolve(input));
    }

    @Test
    void shapeAndRotationArePreservedForFallbackTables() {
        TablePlacement unplacedRound = new TablePlacement(5, null, null, null, null, "ROUND", 90);
        TablePlacement out = LayoutAutoArrange.resolve(List.of(unplacedRound)).get(0);

        assertEquals("ROUND", out.shape());
        assertEquals(90, out.rotationDeg());
        assertTrue(out.isPlaced());
    }

    @Test
    void resolveDoesNotMutateItsInputAndReturnsImmutableList() {
        List<TablePlacement> input = List.of(TablePlacement.unplaced(1));
        List<TablePlacement> out = LayoutAutoArrange.resolve(input);

        assertFalse(input.get(0).isPlaced(), "girdi değişmemeli");
        assertThrows(UnsupportedOperationException.class, () -> out.add(null));
        assertTrue(LayoutAutoArrange.resolve(List.of()).isEmpty());
        assertTrue(LayoutAutoArrange.resolve(null).isEmpty());
    }

    @Test
    void editorArrangeMissingAlsoAvoidsSavedTables() {
        // Önceden editör geçici yuvayı kayıtlı masanın üzerine koyabiliyordu;
        // bu, kaydetme anındaki çakışma denetimini haksız yere tetiklerdi.
        TableLayoutEntry saved = new TableLayoutEntry();
        saved.setTableNo(101);
        saved.setPosX(20);
        saved.setPosY(20);
        saved.setWidth(110);
        saved.setHeight(90);
        saved.setShape("RECT");
        TableLayoutEntry missing = new TableLayoutEntry();
        missing.setTableNo(102);

        List<TableLayoutEntry> tables = new ArrayList<>(List.of(saved, missing));
        LayoutAutoArrange.arrangeMissing(tables);

        assertTrue(LayoutPlacementRules.findOverlap(tables).isEmpty(),
                "editörün geçici yuvası kayıtlı masayla çakışmamalı");
        assertEquals(20, saved.getPosX(), "kayıtlı masa yerinde kalmalı");
    }
}
