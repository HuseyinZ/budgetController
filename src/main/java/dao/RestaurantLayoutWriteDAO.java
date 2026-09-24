package dao;

import model.RestaurantArea;
import model.TableLayoutEntry;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

/**
 * Masa düzeni YAZMA katmanı — okuma katmanından ({@link RestaurantLayoutDAO})
 * bilinçli olarak ayrıdır.
 *
 * <p>Her metot çağıran tarafından açılmış bir {@link Connection} alır: bütün
 * çok satırlı işlemler tek transaction içinde yürütülür ve ara durum diske
 * yansımaz. DAO doğrulama yapmaz; iş kuralları servis katmanındadır.
 *
 * <p>Fiziksel silme YOKTUR: pasifleştirme (soft-deactivate) kullanılır, böylece
 * geçmiş siparişlerin bağlı olduğu masa/alan kayıtları korunur.
 */
public interface RestaurantLayoutWriteDAO {

    // ---- alan ----

    /** Yeni alan ekler ve üretilen id'yi döner. */
    int insertArea(Connection conn, RestaurantArea area);

    /** Alanın adlarını ve sırasını günceller (aktiflik burada değişmez). */
    void updateAreaNamesAndOrder(Connection conn, RestaurantArea area);

    void setAreaActive(Connection conn, int areaId, boolean active);

    Optional<RestaurantArea> findAreaById(Connection conn, int areaId);

    /**
     * TÜM alanlar (aktif + pasif), {@code display_order} → {@code id} sırasıyla.
     * Yönetim ekranı pasif kayıtları da görmek zorundadır.
     */
    List<RestaurantArea> findAllAreasOrdered(Connection conn);

    /** TÜM masalar (aktif + pasif), {@code display_order} → {@code table_no} sırasıyla. */
    List<TableLayoutEntry> findAllTablesOrdered(Connection conn);

    /** Aynı (building, floor, salon) üçlüsüne sahip başka alan var mı? */
    boolean areaKeyExists(Connection conn, String building, String floor, String salon, Integer exceptAreaId);

    // ---- masa ----

    void insertTable(Connection conn, TableLayoutEntry table);

    void setTableActive(Connection conn, int tableNo, boolean active);

    /** Görsel yerleşim + sıra günceller; {@code table_no} ve {@code area_id} değişmez. */
    void updatePlacement(Connection conn, TableLayoutEntry table);

    Optional<TableLayoutEntry> findTableByNo(Connection conn, int tableNo);

    /** Alanın masaları; {@code activeOnly} true ise yalnız aktif olanlar. */
    List<TableLayoutEntry> findTablesByArea(Connection conn, int areaId, boolean activeOnly);
}
