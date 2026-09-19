package service.layout;

import dao.RestaurantLayoutWriteDAO;
import model.RestaurantArea;
import model.Role;
import model.TableLayoutEntry;
import model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Masa düzeni yönetim kuralları: transaction sınırı, bütünlük değişmezleri,
 * kullanımdaki masa koruması ve yetki.
 *
 * <p>Sahte DAO + sahte transaction kullanılır; hiçbir veritabanına bağlanılmaz.
 */
class RestaurantLayoutManagementServiceTest {

    /** Bellek içi DAO — commit/rollback davranışını gözlemek için "staging" tutar. */
    private static final class FakeDao implements RestaurantLayoutWriteDAO {
        final Map<Integer, RestaurantArea> areas = new LinkedHashMap<>();
        final Map<Integer, TableLayoutEntry> tables = new LinkedHashMap<>();
        int nextAreaId = 1;
        final List<String> writes = new ArrayList<>();

        @Override public int insertArea(Connection c, RestaurantArea a) {
            int id = nextAreaId++;
            RestaurantArea copy = copy(a);
            copy.setId(id);
            areas.put(id, copy);
            writes.add("insertArea:" + id + ":active=" + copy.isActive());
            return id;
        }
        @Override public void updateAreaNamesAndOrder(Connection c, RestaurantArea a) {
            RestaurantArea stored = areas.get(a.getId());
            stored.setBuilding(a.getBuilding());
            stored.setFloor(a.getFloor());
            stored.setSalon(a.getSalon());
            stored.setDisplayOrder(a.getDisplayOrder());
            writes.add("updateArea:" + a.getId());
        }
        @Override public void setAreaActive(Connection c, int areaId, boolean active) {
            areas.get(areaId).setActive(active);
            writes.add("setAreaActive:" + areaId + "=" + active);
        }
        @Override public Optional<RestaurantArea> findAreaById(Connection c, int areaId) {
            return Optional.ofNullable(areas.get(areaId));
        }
        @Override public boolean areaKeyExists(Connection c, String b, String f, String s, Integer except) {
            return areas.values().stream().anyMatch(a ->
                    a.getBuilding().equals(b) && a.getFloor().equals(f) && a.getSalon().equals(s)
                            && (except == null || !except.equals(a.getId())));
        }
        @Override public void insertTable(Connection c, TableLayoutEntry t) {
            tables.put(t.getTableNo(), copy(t));
            writes.add("insertTable:" + t.getTableNo());
        }
        @Override public void setTableActive(Connection c, int tableNo, boolean active) {
            tables.get(tableNo).setActive(active);
            writes.add("setTableActive:" + tableNo + "=" + active);
        }
        @Override public void updatePlacement(Connection c, TableLayoutEntry t) {
            TableLayoutEntry stored = tables.get(t.getTableNo());
            stored.setPosX(t.getPosX());
            stored.setPosY(t.getPosY());
            stored.setWidth(t.getWidth());
            stored.setHeight(t.getHeight());
            stored.setShape(t.getShape());
            stored.setRotationDeg(t.getRotationDeg());
            stored.setDisplayOrder(t.getDisplayOrder());
            writes.add("updatePlacement:" + t.getTableNo());
        }
        @Override public Optional<TableLayoutEntry> findTableByNo(Connection c, int tableNo) {
            return Optional.ofNullable(tables.get(tableNo));
        }
        @Override public List<RestaurantArea> findAllAreasOrdered(Connection c) {
            return List.copyOf(areas.values());
        }
        @Override public List<TableLayoutEntry> findAllTablesOrdered(Connection c) {
            return List.copyOf(tables.values());
        }
        @Override public List<TableLayoutEntry> findTablesByArea(Connection c, int areaId, boolean activeOnly) {
            return tables.values().stream()
                    .filter(t -> t.getAreaId() == areaId)
                    .filter(t -> !activeOnly || t.isActive())
                    .toList();
        }

        private static RestaurantArea copy(RestaurantArea a) {
            RestaurantArea x = new RestaurantArea();
            x.setId(a.getId()); x.setBuilding(a.getBuilding()); x.setFloor(a.getFloor());
            x.setSalon(a.getSalon()); x.setDisplayOrder(a.getDisplayOrder()); x.setActive(a.isActive());
            return x;
        }
        private static TableLayoutEntry copy(TableLayoutEntry t) {
            TableLayoutEntry x = new TableLayoutEntry();
            x.setTableNo(t.getTableNo()); x.setAreaId(t.getAreaId());
            x.setPosX(t.getPosX()); x.setPosY(t.getPosY());
            x.setWidth(t.getWidth()); x.setHeight(t.getHeight());
            x.setShape(t.getShape()); x.setRotationDeg(t.getRotationDeg());
            x.setDisplayOrder(t.getDisplayOrder()); x.setActive(t.isActive());
            return x;
        }
    }

    /** Sahte transaction: iş patlarsa "rollback" sayılır ve yazımlar geri alınır. */
    private static final class FakeTransaction implements LayoutTransaction {
        final FakeDao dao;
        int commits;
        int rollbacks;

        FakeTransaction(FakeDao dao) { this.dao = dao; }

        @Override public <T> T execute(Work<T> work) {
            Map<Integer, RestaurantArea> areaBackup = new LinkedHashMap<>();
            dao.areas.forEach((k, v) -> areaBackup.put(k, FakeDao.copy(v)));
            Map<Integer, TableLayoutEntry> tableBackup = new LinkedHashMap<>();
            dao.tables.forEach((k, v) -> tableBackup.put(k, FakeDao.copy(v)));
            int nextId = dao.nextAreaId;
            try {
                T result = work.apply(null);
                commits++;
                return result;
            } catch (Exception ex) {
                dao.areas.clear(); dao.areas.putAll(areaBackup);
                dao.tables.clear(); dao.tables.putAll(tableBackup);
                dao.nextAreaId = nextId;
                rollbacks++;
                throw ex instanceof RuntimeException re ? re : new IllegalStateException(ex);
            }
        }
    }

    private static final User ADMIN = user(Role.ADMIN);
    private static final User KASIYER = user(Role.KASIYER);

    private static User user(Role role) {
        // model.User yalnız (username, passwordHash, role[, fullName]) kurucularına sahip.
        return new User("test-" + role.name().toLowerCase(java.util.Locale.ROOT), "x", role);
    }

    private static RestaurantArea area(String building, String floor, String salon, int order) {
        RestaurantArea a = new RestaurantArea();
        a.setBuilding(building); a.setFloor(floor); a.setSalon(salon); a.setDisplayOrder(order);
        return a;
    }

    private static TableLayoutEntry table(int no, int order) {
        TableLayoutEntry t = new TableLayoutEntry();
        t.setTableNo(no); t.setDisplayOrder(order); t.setShape("RECT");
        return t;
    }

    private FakeDao dao;
    private FakeTransaction tx;
    private java.util.Set<Integer> inUse;
    private RestaurantLayoutManagementService service;

    @BeforeEach
    void setUp() {
        dao = new FakeDao();
        tx = new FakeTransaction(dao);
        inUse = new java.util.HashSet<>();
        service = new RestaurantLayoutManagementService(dao, tx, inUse::contains);
    }

    private int seedArea() {
        return service.createAreaWithTables(ADMIN, area("1. Bina", "1. Kat", "1. Salon", 1),
                new ArrayList<>(List.of(table(101, 1), table(102, 2))));
    }

    // ---------------- yetki ----------------

    @Test
    void onlyAdminMayMutateLayout() {
        assertThrows(SecurityException.class,
                () -> service.createAreaWithTables(KASIYER, area("A", "K", "", 1), List.of(table(1, 1))));
        assertThrows(SecurityException.class,
                () -> service.createAreaWithTables(null, area("A", "K", "", 1), List.of(table(1, 1))));
        assertThrows(SecurityException.class, () -> service.deactivateTable(KASIYER, 101));
        assertEquals(0, tx.commits, "yetkisiz çağrı transaction bile açmamalı");
    }

    // ---------------- oluşturma / transaction ----------------

    @Test
    void areaIsCreatedActiveOnlyAfterItsTablesExist() {
        int id = seedArea();

        assertTrue(dao.areas.get(id).isActive());
        assertEquals(2, dao.findTablesByArea(null, id, true).size());
        // Sıra: alan PASİF eklenir → masalar → sonra aktifleştirilir
        assertEquals(List.of("insertArea:1:active=false", "insertTable:101", "insertTable:102",
                "setAreaActive:1=true"), dao.writes);
        assertEquals(1, tx.commits);
    }

    @Test
    void areaCannotBeCreatedWithoutTables() {
        assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.createAreaWithTables(ADMIN, area("A", "K", "", 1), List.of()));
        assertTrue(dao.areas.isEmpty(), "geçersiz istek hiç yazmamalı");
    }

    @Test
    void failedMultiRowMutationIsRolledBackEntirely() {
        seedArea();
        dao.writes.clear();

        // İkinci masa numarası zaten var → işlem ortasında reddedilir
        assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.createAreaWithTables(ADMIN, area("2. Bina", "1. Kat", "", 2),
                        new ArrayList<>(List.of(table(201, 1), table(101, 2)))));

        assertEquals(1, tx.rollbacks);
        assertEquals(1, dao.areas.size(), "yeni alan kalmamalı");
        assertFalse(dao.tables.containsKey(201), "yarım eklenen masa kalmamalı");
    }

    @Test
    void duplicateTableNumbersInOneRequestAreRejected() {
        assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.createAreaWithTables(ADMIN, area("A", "K", "", 1),
                        new ArrayList<>(List.of(table(5, 1), table(5, 2)))));
    }

    @Test
    void invalidTableNumberIsRejected() {
        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> service.createAreaWithTables(ADMIN, area("A", "K", "", 1),
                        new ArrayList<>(List.of(table(0, 1)))));
    }

    @Test
    void duplicateAreaKeyIsRejected() {
        seedArea();
        assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.createAreaWithTables(ADMIN, area("1. Bina", "1. Kat", "1. Salon", 5),
                        new ArrayList<>(List.of(table(999, 1)))));
    }

    @Test
    void nonContiguousTableNumbersAreSupported() {
        int id = service.createAreaWithTables(ADMIN, area("Bahçe", "Zemin", "", 1),
                new ArrayList<>(List.of(table(7, 1), table(19, 2), table(4020, 3))));
        assertEquals(List.of(7, 19, 4020),
                dao.findTablesByArea(null, id, true).stream().map(TableLayoutEntry::getTableNo).toList());
    }

    // ---------------- değişmezler ----------------

    @Test
    void lastActiveTableOfAnActiveAreaCannotBeDeactivated() {
        int id = seedArea();
        service.deactivateTable(ADMIN, 101);

        var ex = assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.deactivateTable(ADMIN, 102));
        assertTrue(ex.getMessage().contains("son aktif masası"), ex.getMessage());
        assertTrue(dao.tables.get(102).isActive(), "masa aktif kalmalı");
        assertTrue(dao.areas.get(id).isActive());
    }

    @Test
    void deactivatingAnAreaAlsoDeactivatesItsTablesInOneTransaction() {
        int id = seedArea();
        dao.writes.clear();
        // tx.commits KÜMÜLATİFTİR: fixture'ın kendisi (alan + 2 masa oluşturma)
        // zaten meşru bir commit yapar. Ölçülmek istenen, pasifleştirmenin TEK
        // transaction olduğudur → artış 1 olmalı.
        int commitsBefore = tx.commits;
        int rollbacksBefore = tx.rollbacks;

        service.deactivateArea(ADMIN, id);

        assertFalse(dao.areas.get(id).isActive());
        assertTrue(dao.tables.values().stream().noneMatch(TableLayoutEntry::isActive),
                "pasif alanda aktif masa kalamaz");
        assertEquals(commitsBefore + 1, tx.commits,
                "alan + iki masa tek transaction'da pasifleşmeli");
        assertEquals(rollbacksBefore, tx.rollbacks);
        // Tüm kullanım kontrolleri ilk yazmadan ÖNCE yapılır, sonra yazmalar gelir
        assertEquals(List.of("setTableActive:101=false", "setTableActive:102=false",
                "setAreaActive:1=false"), dao.writes);
    }

    @Test
    void tableCannotBeActivatedInsideAnInactiveArea() {
        int id = seedArea();
        service.deactivateArea(ADMIN, id);

        assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.reactivateTable(ADMIN, 101));
        assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.addTable(ADMIN, id, table(103, 3)));
    }

    @Test
    void areaReactivationRequiresAtLeastOneTable() {
        int id = seedArea();
        service.deactivateArea(ADMIN, id);

        assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.reactivateArea(ADMIN, id, List.of()));

        service.reactivateArea(ADMIN, id, List.of(101));
        assertTrue(dao.areas.get(id).isActive());
        assertTrue(dao.tables.get(101).isActive());
        assertFalse(dao.tables.get(102).isActive(), "istenmeyen masa aktifleşmemeli");
    }

    // ---------------- operasyonel durum koruması ----------------

    @Test
    void tableInUseCannotBeDeactivated() {
        seedArea();
        inUse.add(101);

        var ex = assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.deactivateTable(ADMIN, 101));
        assertTrue(ex.getMessage().contains("kullanımda"), ex.getMessage());
        assertTrue(dao.tables.get(101).isActive());
    }

    @Test
    void areaWithAnInUseTableCannotBeDeactivatedAndNothingChanges() {
        int id = seedArea();
        inUse.add(102);
        dao.writes.clear();

        assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.deactivateArea(ADMIN, id));

        assertTrue(dao.areas.get(id).isActive(), "alan aktif kalmalı");
        assertTrue(dao.tables.values().stream().allMatch(TableLayoutEntry::isActive),
                "hiçbir masa pasifleşmemeli");
        assertEquals(1, tx.rollbacks);
    }

    // ---------------- yönetim okuma yolu ----------------

    @Test
    void managementViewShowsActiveAndInactiveRecordsToAdminOnly() {
        int id = seedArea();
        service.deactivateTable(ADMIN, 101);

        assertThrows(SecurityException.class, () -> service.loadForManagement(KASIYER));

        var view = service.loadForManagement(ADMIN);
        assertEquals(1, view.areas().size());
        assertEquals(2, view.tablesOf(id).size(), "pasif masa da görünmeli");
        assertTrue(view.tablesOf(id).stream().anyMatch(t -> !t.isActive()));
    }

    @Test
    void managementViewIsImmutable() {
        seedArea();
        var view = service.loadForManagement(ADMIN);
        assertThrows(UnsupportedOperationException.class, () -> view.areas().clear());
        assertThrows(UnsupportedOperationException.class, () -> view.tables().clear());
    }

    // ---------------- yerleşim ----------------

    @Test
    void placementUpdatesAreValidatedAndBatched() {
        seedArea();
        TableLayoutEntry a = table(101, 1);
        a.setPosX(10); a.setPosY(20); a.setWidth(100); a.setHeight(50);
        TableLayoutEntry b = table(102, 2);
        b.setPosX(200); b.setPosY(20); b.setWidth(100); b.setHeight(50);

        service.updatePlacements(ADMIN, List.of(a, b));

        assertEquals(10, dao.tables.get(101).getPosX());
        assertEquals(200, dao.tables.get(102).getPosX());
    }

    @Test
    void oneInvalidPlacementRejectsTheWholeBatch() {
        seedArea();
        TableLayoutEntry ok = table(101, 1);
        ok.setPosX(10); ok.setPosY(10); ok.setWidth(10); ok.setHeight(10);
        TableLayoutEntry bad = table(102, 2);
        bad.setPosX(10); bad.setPosY(10); bad.setWidth(10); bad.setHeight(10);
        bad.setRotationDeg(400);

        assertThrows(LayoutPlacementRules.InvalidPlacementException.class,
                () -> service.updatePlacements(ADMIN, List.of(ok, bad)));
        assertEquals(null, dao.tables.get(101).getPosX(),
                "doğrulama transaction'dan ÖNCE yapılmalı, hiç yazılmamalı");
    }

    /** Konumlandırılmış masa üretir. */
    private static TableLayoutEntry placed(int no, int order, int x, int y, int w, int h) {
        TableLayoutEntry t = table(no, order);
        t.setPosX(x); t.setPosY(y); t.setWidth(w); t.setHeight(h);
        return t;
    }

    @Test
    void serviceRejectsAnOverlappingBatchWithoutRelyingOnTheUi() {
        seedArea();
        dao.writes.clear();
        int rollbacksBefore = tx.rollbacks;

        var ex = assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.updatePlacements(ADMIN, List.of(
                        placed(101, 1, 0, 0, 100, 100),
                        placed(102, 2, 50, 50, 100, 100))));

        assertTrue(ex.getMessage().contains("üst üste"), ex.getMessage());
        assertTrue(dao.writes.isEmpty(), "çakışmada hiçbir yazma olmamalı: " + dao.writes);
        assertEquals(rollbacksBefore + 1, tx.rollbacks);
    }

    @Test
    void overlapAgainstAnUnchangedExistingTableIsRejected() {
        seedArea();
        // 101 sabit konumda; 102 onun üzerine taşınmaya çalışılıyor
        service.updatePlacement(ADMIN, placed(101, 1, 0, 0, 100, 100));
        dao.writes.clear();

        assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.updatePlacements(ADMIN, List.of(placed(102, 2, 20, 20, 100, 100))));

        assertTrue(dao.writes.isEmpty(), "değişmeyen masa da hesaba katılmalı: " + dao.writes);
        assertTrue(dao.tables.get(102).getPosX() == null, "102 konumlandırılmamış kalmalı");
    }

    @Test
    void nonOverlappingBatchIsAccepted() {
        seedArea();
        dao.writes.clear();

        service.updatePlacements(ADMIN, List.of(
                placed(101, 1, 0, 0, 100, 100),
                placed(102, 2, 100, 0, 100, 100)));   // kenar teması → çakışma değil

        assertEquals(List.of("updatePlacement:101", "updatePlacement:102"), dao.writes);
        assertEquals(0, dao.tables.get(101).getPosX());
        assertEquals(100, dao.tables.get(102).getPosX());
    }

    @Test
    void inactiveTablesAreIgnoredByOverlapValidation() {
        seedArea();
        service.updatePlacement(ADMIN, placed(101, 1, 0, 0, 100, 100));
        service.deactivateTable(ADMIN, 101);   // artık runtime düzende yok
        dao.writes.clear();

        // 102 pasif masanın üzerine taşınabilir: görsel semantik yalnız aktifleri sayar
        service.updatePlacements(ADMIN, List.of(placed(102, 2, 10, 10, 100, 100)));

        assertEquals(List.of("updatePlacement:102"), dao.writes);
    }

    @Test
    void placementOfUnknownTableIsRejectedAndRolledBack() {
        seedArea();
        TableLayoutEntry missing = table(777, 1);

        assertThrows(RestaurantLayoutManagementService.LayoutRuleViolationException.class,
                () -> service.updatePlacement(ADMIN, missing));
        assertEquals(1, tx.rollbacks);
    }

    @Test
    void tableNumberIsImmutable() {
        // Yerleşim güncellemesi table_no/area_id'ye dokunmaz: DAO sözleşmesi
        // yalnız görsel alanları yazar.
        seedArea();
        TableLayoutEntry move = table(101, 9);
        move.setAreaId(42);
        service.updatePlacement(ADMIN, move);

        assertEquals(1, dao.tables.get(101).getAreaId(), "alan değişmemeli");
        assertTrue(dao.tables.containsKey(101), "masa numarası korunmalı");
    }
}
