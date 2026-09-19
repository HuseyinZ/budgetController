package service.layout;

import dao.RestaurantLayoutWriteDAO;
import dao.jdbc.RestaurantLayoutWriteJdbcDAO;
import model.Role;
import model.RestaurantArea;
import model.TableLayoutEntry;
import model.User;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/**
 * Masa düzeni YÖNETİMİ — yönetici (ADMIN) işlemleri.
 *
 * <p>Bu sınıf gelecekteki yönetim ekranının arka ucudur. Bilinçli olarak
 * yalnız servis seviyesindedir: API/PWA'ya açılmaz, çünkü bu uçlar için
 * kimlik doğrulamalı bir yönetim sınırı henüz yok.
 *
 * <p><b>Değişmezler.</b> Hiçbir işlem, {@code RestaurantLayoutService
 * .loadActiveLayout()}'un reddedeceği bir duruma yol açamaz:
 * <ul>
 *   <li>aktif alanın en az bir aktif masası olur</li>
 *   <li>aktif masa pasif alana bağlı olamaz</li>
 *   <li>masa numarası benzersiz ve pozitiftir; numara DEĞİŞMEZ</li>
 *   <li>sıralama deterministiktir ({@code display_order})</li>
 * </ul>
 * Alan, önce pasif (taslak) olarak kurulup masalarıyla birlikte tek
 * transaction'da aktifleştirilir; ara durum diske yansımaz.
 *
 * <p>Fiziksel silme yoktur: pasifleştirme kullanılır, böylece geçmiş
 * siparişlerin bağlı olduğu kayıtlar korunur.
 */
public class RestaurantLayoutManagementService {

    /** Masa POS'ta kullanımda mı? (açık sipariş / dolu-rezerve durum) */
    @FunctionalInterface
    public interface TableUsageCheck {
        boolean isInUse(int tableNo);
    }

    /** İş kuralı ihlali — işlem yapılmadan reddedilir. */
    public static class LayoutRuleViolationException extends IllegalStateException {
        public LayoutRuleViolationException(String message) {
            super(message);
        }
    }

    private final RestaurantLayoutWriteDAO dao;
    private final LayoutTransaction tx;
    private final TableUsageCheck usageCheck;

    public RestaurantLayoutManagementService(TableUsageCheck usageCheck) {
        this(new RestaurantLayoutWriteJdbcDAO(), LayoutTransaction.usingApplicationPool(), usageCheck);
    }

    public RestaurantLayoutManagementService(RestaurantLayoutWriteDAO dao,
                                             LayoutTransaction tx,
                                             TableUsageCheck usageCheck) {
        this.dao = dao;
        this.tx = tx;
        this.usageCheck = usageCheck;
    }

    // ==================================================================
    //  Alan işlemleri
    // ==================================================================

    /**
     * Yeni alanı masalarıyla birlikte oluşturur — tek transaction.
     *
     * <p>En az bir masa zorunludur: masasız aktif alan düzeni geçersiz kılar.
     * Alan önce pasif yazılır, masalar eklenir, sonra aktifleştirilir.
     *
     * @return üretilen alan kimliği
     */
    public int createAreaWithTables(User user, RestaurantArea area, List<TableLayoutEntry> tables) {
        requireAdmin(user, "masa düzeni alanı ekle");
        requireArea(area);
        if (tables == null || tables.isEmpty()) {
            throw new LayoutRuleViolationException(
                    "Alan en az bir masa ile oluşturulmalı (masasız aktif alan geçersizdir)");
        }
        for (TableLayoutEntry t : tables) {
            LayoutPlacementRules.validateAndNormalize(t);
        }
        requireDistinctTableNumbers(tables);

        return tx.execute(conn -> {
            if (dao.areaKeyExists(conn, area.getBuilding(), area.getFloor(), area.getSalon(), null)) {
                throw new LayoutRuleViolationException("Bu bina/kat/salon zaten tanımlı");
            }
            for (TableLayoutEntry t : tables) {
                if (dao.findTableByNo(conn, t.getTableNo()).isPresent()) {
                    throw new LayoutRuleViolationException(
                            "Masa numarası zaten kullanılıyor: " + t.getTableNo());
                }
            }
            // Taslak: alan önce PASİF yazılır
            area.setActive(false);
            int areaId = dao.insertArea(conn, area);
            for (TableLayoutEntry t : tables) {
                t.setAreaId(areaId);
                t.setActive(true);
                dao.insertTable(conn, t);
            }
            // Masalar yerindeyken aktifleştir → ara durum kalıcı olmaz
            dao.setAreaActive(conn, areaId, true);
            return areaId;
        });
    }

    /** Alan adlarını ve sırasını günceller; aktiflik ve masalar etkilenmez. */
    public void updateArea(User user, RestaurantArea area) {
        requireAdmin(user, "masa düzeni alanı düzenle");
        requireArea(area);
        if (area.getId() == null) {
            throw new LayoutRuleViolationException("Alan kimliği gerekli");
        }
        tx.execute(conn -> {
            RestaurantArea current = dao.findAreaById(conn, area.getId())
                    .orElseThrow(() -> new LayoutRuleViolationException("Alan bulunamadı: " + area.getId()));
            if (dao.areaKeyExists(conn, area.getBuilding(), area.getFloor(), area.getSalon(), area.getId())) {
                throw new LayoutRuleViolationException("Bu bina/kat/salon zaten tanımlı");
            }
            area.setActive(current.isActive());
            dao.updateAreaNamesAndOrder(conn, area);
            return null;
        });
    }

    /**
     * Alanı ve TÜM aktif masalarını pasifleştirir — tek transaction.
     *
     * <p>Masalardan biri kullanımdaysa işlem hiç başlamaz: POS'ta açık
     * siparişi olan bir masa ekrandan kaybolmamalıdır.
     */
    public void deactivateArea(User user, int areaId) {
        requireAdmin(user, "masa düzeni alanı pasifleştir");
        tx.execute(conn -> {
            dao.findAreaById(conn, areaId)
                    .orElseThrow(() -> new LayoutRuleViolationException("Alan bulunamadı: " + areaId));
            List<TableLayoutEntry> active = dao.findTablesByArea(conn, areaId, true);
            for (TableLayoutEntry t : active) {
                requireNotInUse(t.getTableNo(), "alan pasifleştirilemez");
            }
            for (TableLayoutEntry t : active) {
                dao.setTableActive(conn, t.getTableNo(), false);
            }
            dao.setAreaActive(conn, areaId, false);
            return null;
        });
    }

    /**
     * Alanı, belirtilen masalarla birlikte yeniden aktifleştirir.
     *
     * <p>En az bir masa aktifleştirilmelidir; masasız aktif alan düzeni
     * geçersiz kılardı.
     */
    public void reactivateArea(User user, int areaId, List<Integer> tableNumbers) {
        requireAdmin(user, "masa düzeni alanı aktifleştir");
        if (tableNumbers == null || tableNumbers.isEmpty()) {
            throw new LayoutRuleViolationException(
                    "Alan en az bir aktif masa ile açılmalı");
        }
        tx.execute(conn -> {
            dao.findAreaById(conn, areaId)
                    .orElseThrow(() -> new LayoutRuleViolationException("Alan bulunamadı: " + areaId));
            for (Integer tableNo : tableNumbers) {
                TableLayoutEntry t = dao.findTableByNo(conn, tableNo)
                        .orElseThrow(() -> new LayoutRuleViolationException("Masa bulunamadı: " + tableNo));
                if (t.getAreaId() != areaId) {
                    throw new LayoutRuleViolationException(
                            "Masa " + tableNo + " bu alana ait değil");
                }
            }
            dao.setAreaActive(conn, areaId, true);
            for (Integer tableNo : tableNumbers) {
                dao.setTableActive(conn, tableNo, true);
            }
            return null;
        });
    }

    // ==================================================================
    //  Masa işlemleri
    // ==================================================================

    /** Aktif bir alana yeni masa ekler. Masa numarası benzersiz ve kalıcıdır. */
    public void addTable(User user, int areaId, TableLayoutEntry table) {
        requireAdmin(user, "masa ekle");
        LayoutPlacementRules.validateAndNormalize(table);
        tx.execute(conn -> {
            RestaurantArea area = dao.findAreaById(conn, areaId)
                    .orElseThrow(() -> new LayoutRuleViolationException("Alan bulunamadı: " + areaId));
            if (!area.isActive()) {
                throw new LayoutRuleViolationException(
                        "Pasif alana aktif masa eklenemez; önce alanı aktifleştirin");
            }
            if (dao.findTableByNo(conn, table.getTableNo()).isPresent()) {
                throw new LayoutRuleViolationException(
                        "Masa numarası zaten kullanılıyor: " + table.getTableNo());
            }
            table.setAreaId(areaId);
            table.setActive(true);
            dao.insertTable(conn, table);
            return null;
        });
    }

    /**
     * Masayı pasifleştirir.
     *
     * <p>İki koruma: (1) masa POS'ta kullanımdaysa reddedilir, (2) aktif bir
     * alanın SON aktif masası pasifleştirilemez — alan masasız kalırdı.
     */
    public void deactivateTable(User user, int tableNo) {
        requireAdmin(user, "masa pasifleştir");
        tx.execute(conn -> {
            TableLayoutEntry table = dao.findTableByNo(conn, tableNo)
                    .orElseThrow(() -> new LayoutRuleViolationException("Masa bulunamadı: " + tableNo));
            if (!table.isActive()) {
                return null;   // zaten pasif — işlem idempotent
            }
            requireNotInUse(tableNo, "masa pasifleştirilemez");

            RestaurantArea area = dao.findAreaById(conn, table.getAreaId())
                    .orElseThrow(() -> new LayoutRuleViolationException(
                            "Masanın alanı bulunamadı: " + table.getAreaId()));
            if (area.isActive()) {
                long remaining = dao.findTablesByArea(conn, table.getAreaId(), true).stream()
                        .filter(t -> t.getTableNo() != tableNo)
                        .count();
                if (remaining == 0) {
                    throw new LayoutRuleViolationException(
                            "Alanın son aktif masası pasifleştirilemez; önce alanı pasifleştirin");
                }
            }
            dao.setTableActive(conn, tableNo, false);
            return null;
        });
    }

    /** Masayı yeniden aktifleştirir; alan aktif değilse reddedilir. */
    public void reactivateTable(User user, int tableNo) {
        requireAdmin(user, "masa aktifleştir");
        tx.execute(conn -> {
            TableLayoutEntry table = dao.findTableByNo(conn, tableNo)
                    .orElseThrow(() -> new LayoutRuleViolationException("Masa bulunamadı: " + tableNo));
            RestaurantArea area = dao.findAreaById(conn, table.getAreaId())
                    .orElseThrow(() -> new LayoutRuleViolationException(
                            "Masanın alanı bulunamadı: " + table.getAreaId()));
            if (!area.isActive()) {
                throw new LayoutRuleViolationException(
                        "Pasif alanın masası aktifleştirilemez; önce alanı aktifleştirin");
            }
            dao.setTableActive(conn, tableNo, true);
            return null;
        });
    }

    /** Görsel yerleşimi ve sırayı günceller. Masa numarası ve alan değişmez. */
    public void updatePlacement(User user, TableLayoutEntry table) {
        requireAdmin(user, "masa yerleşimi güncelle");
        LayoutPlacementRules.validateAndNormalize(table);
        tx.execute(conn -> {
            dao.findTableByNo(conn, table.getTableNo())
                    .orElseThrow(() -> new LayoutRuleViolationException(
                            "Masa bulunamadı: " + table.getTableNo()));
            dao.updatePlacement(conn, table);
            return null;
        });
    }

    /** Birden çok masanın yerleşimini TEK transaction'da günceller (editör kaydı). */
    public void updatePlacements(User user, List<TableLayoutEntry> tables) {
        requireAdmin(user, "masa yerleşimi güncelle");
        if (tables == null || tables.isEmpty()) {
            return;
        }
        for (TableLayoutEntry t : tables) {
            LayoutPlacementRules.validateAndNormalize(t);
        }
        requireDistinctTableNumbers(tables);
        tx.execute(conn -> {
            for (TableLayoutEntry t : tables) {
                dao.findTableByNo(conn, t.getTableNo())
                        .orElseThrow(() -> new LayoutRuleViolationException(
                                "Masa bulunamadı: " + t.getTableNo()));
            }
            for (TableLayoutEntry t : tables) {
                dao.updatePlacement(conn, t);
            }
            return null;
        });
    }

    // ==================================================================

    private void requireNotInUse(int tableNo, String action) {
        if (usageCheck != null && usageCheck.isInUse(tableNo)) {
            throw new LayoutRuleViolationException(
                    "Masa " + tableNo + " kullanımda (açık sipariş veya dolu/rezerve) — " + action);
        }
    }

    private static void requireArea(RestaurantArea area) {
        if (area == null) {
            throw new LayoutRuleViolationException("Alan boş olamaz");
        }
        if (area.getBuilding() == null || area.getBuilding().isBlank()) {
            throw new LayoutRuleViolationException("Bina adı zorunlu");
        }
        if (area.getFloor() == null || area.getFloor().isBlank()) {
            throw new LayoutRuleViolationException("Kat adı zorunlu");
        }
        if (area.getDisplayOrder() < 0) {
            throw new LayoutRuleViolationException("Sıra negatif olamaz");
        }
    }

    private static void requireDistinctTableNumbers(List<TableLayoutEntry> tables) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (TableLayoutEntry t : tables) {
            if (!seen.add(t.getTableNo())) {
                throw new LayoutRuleViolationException(
                        "Masa numarası yinelenmiş: " + t.getTableNo());
            }
        }
    }

    /** Yönetim işlemleri yalnız ADMIN'e açıktır (projedeki yetki deseni). */
    private static void requireAdmin(User user, String actionDesc) {
        if (user == null) {
            throw new SecurityException("Kullanıcı bilinmiyor, '" + actionDesc + "' yetkisi yok");
        }
        Role role = user.getRole();
        if (role != Role.ADMIN) {
            throw new SecurityException("'" + actionDesc + "' işlemi için Admin yetkisi gerekir");
        }
    }

    /** Üretim kullanım kontrolü: açık sipariş veya EMPTY olmayan masa durumu. */
    public static TableUsageCheck usageCheckFrom(service.RestaurantTableService tableService,
                                                 service.OrderService orderService) {
        return tableNo -> {
            Optional<model.RestaurantTable> table = tableService.getByTableNo(tableNo);
            if (table.isEmpty() || table.get().getId() == null) {
                return false;   // DB kaydı yok → kullanımda olamaz
            }
            if (table.get().getStatus() != null && table.get().getStatus() != model.TableStatus.EMPTY) {
                return true;
            }
            return orderService.getOpenOrderByTable(table.get().getId()).isPresent();
        };
    }
}
