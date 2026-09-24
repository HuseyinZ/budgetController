package state;

import model.RestaurantArea;
import model.TablePlacement;
import org.junit.jupiter.api.Test;
import service.RestaurantLayoutService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Yerleşim alanları servis → {@code AppState.LayoutSnapshot} yolunda
 * kaybolmamalı ve anlık görüntü değişmez kalmalı.
 */
class LayoutPlacementSnapshotTest {

    private static RestaurantArea area() {
        RestaurantArea a = new RestaurantArea();
        a.setId(1);
        a.setBuilding("1. Bina");
        a.setFloor("1. Kat");
        a.setSalon("1. Salon");
        return a;
    }

    private static RestaurantLayoutService.AreaTables entry() {
        return new RestaurantLayoutService.AreaTables(area(), List.of(101, 7, 4020), List.of(
                new TablePlacement(101, 10, 20, 110, 90, "RECT", 0),
                new TablePlacement(7, 300, 400, 100, 100, "ROUND", 90),
                TablePlacement.unplaced(4020)));
    }

    @Test
    void placementFieldsReachTheSnapshot() {
        AppState.LayoutSnapshot snapshot = AppState.buildSnapshot(List.of(entry()));

        AppState.AreaDefinition area = snapshot.areas().get(0);
        TablePlacement round = area.getPlacement(7).orElseThrow();
        assertEquals(300, round.posX());
        assertEquals(400, round.posY());
        assertEquals(100, round.width());
        assertEquals(100, round.height());
        assertEquals("ROUND", round.shape());
        assertEquals(90, round.rotationDeg());

        assertTrue(area.getPlacement(101).orElseThrow().isPlaced());
        assertFalse(area.getPlacement(4020).orElseThrow().isPlaced(),
                "konumsuz masa konumsuz kalmalı (DB'ye yazılmadan geçici yerleşim alır)");
    }

    @Test
    void placementsKeepTableOrderAndNonContiguousNumbers() {
        AppState.AreaDefinition area = AppState.buildSnapshot(List.of(entry())).areas().get(0);
        assertEquals(List.of(101, 7, 4020), area.getTableNumbers());
        assertEquals(List.of(101, 7, 4020),
                area.getPlacements().stream().map(TablePlacement::tableNo).toList());
    }

    @Test
    void snapshotPlacementsAreImmutable() {
        AppState.AreaDefinition area = AppState.buildSnapshot(List.of(entry())).areas().get(0);
        assertThrows(UnsupportedOperationException.class, () -> area.getPlacements().add(null));
        assertThrows(UnsupportedOperationException.class, () -> area.getTableNumbers().add(1));
    }

    @Test
    void missingPlacementDefaultsToUnplacedAndForeignOnesAreIgnored() {
        AppState.AreaDefinition area = new AppState.AreaDefinition("A", "Kat", "",
                List.of(1, 2),
                List.of(new TablePlacement(1, 0, 0, 50, 50, "RECT", 0),
                        new TablePlacement(999, 0, 0, 50, 50, "RECT", 0)));   // bu alana ait değil

        assertTrue(area.getPlacement(1).orElseThrow().isPlaced());
        assertFalse(area.getPlacement(2).orElseThrow().isPlaced(), "eksik yerleşim → konumsuz");
        assertTrue(area.getPlacement(999).isEmpty(), "yabancı yerleşim yok sayılmalı");
        assertEquals(2, area.getPlacements().size());
    }

    @Test
    void tableLookupMatchesAreasInTheSameSnapshot() {
        AppState.LayoutSnapshot snapshot = AppState.buildSnapshot(List.of(entry()));
        assertEquals(List.of(101, 7, 4020), List.copyOf(snapshot.tableNumbers()));
        assertTrue(snapshot.contains(4020));
    }

    @Test
    void databaseLoaderDelegatesToThePureBuilder() throws IOException {
        String source = Files.readString(
                Path.of("src", "main", "java", "state", "AppState.java"), StandardCharsets.UTF_8);
        assertTrue(source.contains("return buildSnapshot(layoutService.loadActiveLayout());"),
                "DB yükleyicisi test edilen saf kurucuyu kullanmalı");
        assertTrue(source.contains("entry.placements()"),
                "yerleşimler AreaDefinition'a aktarılmalı");
    }
}
