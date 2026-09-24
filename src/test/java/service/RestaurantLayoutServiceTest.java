package service;

import dao.RestaurantLayoutDAO;
import model.RestaurantArea;
import model.TableLayoutEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Masa düzeninin tek kaynağı DB'dir: sıra, bitişik olmayan numaralar,
 * pasif kayıtların dışlanması ve tutarsızlıkta sessiz yedeğe düşmeme.
 */
class RestaurantLayoutServiceTest {

    private static RestaurantArea area(int id, String building, String floor, String salon, int order) {
        RestaurantArea a = new RestaurantArea();
        a.setId(id);
        a.setBuilding(building);
        a.setFloor(floor);
        a.setSalon(salon);
        a.setDisplayOrder(order);
        a.setActive(true);
        return a;
    }

    private static TableLayoutEntry table(int tableNo, int areaId, int order) {
        TableLayoutEntry t = new TableLayoutEntry();
        t.setTableNo(tableNo);
        t.setAreaId(areaId);
        t.setDisplayOrder(order);
        t.setActive(true);
        return t;
    }

    /** DAO zaten yalnız aktif kayıtları döner; test bunu taklit eder. */
    private static RestaurantLayoutDAO dao(List<RestaurantArea> areas, List<TableLayoutEntry> tables) {
        return new RestaurantLayoutDAO() {
            @Override public List<RestaurantArea> findActiveAreasOrdered() { return areas; }
            @Override public List<TableLayoutEntry> findActiveTablesOrdered() { return tables; }
        };
    }

    private static RestaurantLayoutService service(List<RestaurantArea> areas, List<TableLayoutEntry> tables) {
        return new RestaurantLayoutService(dao(areas, tables));
    }

    @Test
    void mapsBuildingFloorSalonAndTableNumbers() {
        var layout = service(
                List.of(area(1, "1. Bina", "1. Kat", "1. Salon", 1)),
                List.of(table(101, 1, 1), table(102, 1, 2))).loadActiveLayout();

        assertEquals(1, layout.size());
        assertEquals("1. Bina", layout.get(0).area().getBuilding());
        assertEquals("1. Kat", layout.get(0).area().getFloor());
        assertEquals("1. Salon", layout.get(0).area().getSalon());
        assertEquals(List.of(101, 102), layout.get(0).tableNumbers());
    }

    @Test
    void placementFieldsSurviveTheService() {
        TableLayoutEntry placed = table(101, 1, 1);
        placed.setPosX(120);
        placed.setPosY(340);
        placed.setWidth(150);
        placed.setHeight(90);
        placed.setShape("round");
        placed.setRotationDeg(45);
        TableLayoutEntry unplaced = table(102, 1, 2);

        var layout = service(List.of(area(1, "A", "Kat", "", 1)),
                List.of(placed, unplaced)).loadActiveLayout();

        var placements = layout.get(0).placements();
        assertEquals(2, placements.size(), "her masa için bir yerleşim");
        assertEquals(List.of(101, 102),
                placements.stream().map(model.TablePlacement::tableNo).toList(),
                "yerleşimler masa sırasıyla hizalı olmalı");

        model.TablePlacement p = placements.get(0);
        assertEquals(120, p.posX());
        assertEquals(340, p.posY());
        assertEquals(150, p.width());
        assertEquals(90, p.height());
        assertEquals("ROUND", p.shape(), "şekil normalize edilmeli");
        assertEquals(45, p.rotationDeg());
        assertTrue(p.isPlaced());
        assertTrue(!placements.get(1).isPlaced(), "konumsuz masa konumsuz kalmalı");

        assertThrows(UnsupportedOperationException.class, () -> placements.add(null));
    }

    @Test
    void supportsNonContiguousTableNumbers() {
        var layout = service(
                List.of(area(1, "A", "Kat", "", 1)),
                List.of(table(7, 1, 1), table(19, 1, 2), table(4020, 1, 3))).loadActiveLayout();

        assertEquals(List.of(7, 19, 4020), layout.get(0).tableNumbers(),
                "numaralar bitişik olmak zorunda değil");
    }

    @Test
    void preservesDatabaseOrderingDeterministically() {
        // DAO sırası display_order'dır; servis bu sırayı bozmamalı.
        var layout = service(
                List.of(area(9, "B", "Kat", "", 1), area(3, "A", "Kat", "", 2)),
                List.of(table(301, 9, 1), table(101, 3, 1), table(302, 9, 2))).loadActiveLayout();

        assertEquals(List.of("B", "A"), List.of(
                layout.get(0).area().getBuilding(), layout.get(1).area().getBuilding()));
        assertEquals(List.of(301, 302), layout.get(0).tableNumbers());
        assertEquals(List.of(101), layout.get(1).tableNumbers());
    }

    @Test
    void tableBoundToAnInactiveOrUnknownAreaIsRejected() {
        // Alan pasif olduğu için DAO onu döndürmez; masası hâlâ görünür.
        var service = service(
                List.of(area(1, "A", "Kat", "", 1)),
                List.of(table(101, 1, 1), table(999, 42, 2)));

        var ex = assertThrows(RestaurantLayoutService.LayoutUnavailableException.class,
                service::loadActiveLayout);
        assertTrue(ex.getMessage().contains("999"), ex.getMessage());
    }

    @Test
    void inactiveRowsNeverReachTheLayout() {
        // DAO aktif olmayanları filtreler → düzen yalnız aktif olanları içerir.
        var layout = service(
                List.of(area(1, "A", "Kat", "", 1)),
                List.of(table(101, 1, 1))).loadActiveLayout();

        assertEquals(1, layout.size());
        assertEquals(List.of(101), layout.get(0).tableNumbers());
    }

    @Test
    void emptyLayoutFailsInsteadOfFallingBack() {
        assertThrows(RestaurantLayoutService.LayoutUnavailableException.class,
                () -> service(List.of(), List.of(table(101, 1, 1))).loadActiveLayout());
        assertThrows(RestaurantLayoutService.LayoutUnavailableException.class,
                () -> service(List.of(area(1, "A", "Kat", "", 1)), List.of()).loadActiveLayout());
    }

    @Test
    void duplicateTableNumberIsRejected() {
        var service = service(
                List.of(area(1, "A", "Kat", "", 1), area(2, "B", "Kat", "", 2)),
                List.of(table(101, 1, 1), table(101, 2, 1)));

        var ex = assertThrows(RestaurantLayoutService.LayoutUnavailableException.class,
                service::loadActiveLayout);
        assertTrue(ex.getMessage().contains("yinelenmiş"), ex.getMessage());
    }

    @Test
    void invalidTableNumberIsRejected() {
        var service = service(
                List.of(area(1, "A", "Kat", "", 1)),
                List.of(table(0, 1, 1)));

        assertThrows(RestaurantLayoutService.LayoutUnavailableException.class,
                service::loadActiveLayout);
    }

    @Test
    void areaWithoutAnyActiveTableIsRejected() {
        var service = service(
                List.of(area(1, "A", "Kat", "", 1), area(2, "B", "Kat", "", 2)),
                List.of(table(101, 1, 1)));

        var ex = assertThrows(RestaurantLayoutService.LayoutUnavailableException.class,
                service::loadActiveLayout);
        assertTrue(ex.getMessage().contains("aktif masa yok"), ex.getMessage());
    }

    @Test
    void databaseFailureIsSurfacedNotSwallowed() {
        RestaurantLayoutDAO failing = new RestaurantLayoutDAO() {
            @Override public List<RestaurantArea> findActiveAreasOrdered() {
                throw new RuntimeException(new java.sql.SQLException("down", "08S01", 0));
            }
            @Override public List<TableLayoutEntry> findActiveTablesOrdered() { return List.of(); }
        };
        assertThrows(RestaurantLayoutService.LayoutUnavailableException.class,
                () -> new RestaurantLayoutService(failing).loadActiveLayout());
    }

    @Test
    void resultIsImmutable() {
        var layout = service(
                List.of(area(1, "A", "Kat", "", 1)),
                new ArrayList<>(List.of(table(101, 1, 1)))).loadActiveLayout();

        assertThrows(UnsupportedOperationException.class, () -> layout.get(0).tableNumbers().add(999));
    }
}
