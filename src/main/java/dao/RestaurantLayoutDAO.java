package dao;

import model.RestaurantArea;
import model.TableLayoutEntry;

import java.util.List;

/**
 * Masa düzeni okuyucusu — {@code restaurant_areas} + {@code restaurant_table_layout}.
 *
 * <p>Bu aşamada YALNIZ OKUMA. Düzen yönetimi (ekle/taşı/pasifleştir) ayrı bir
 * iş kalemidir; seed migration ile gelir.
 */
public interface RestaurantLayoutDAO {

    /** Aktif alanlar; {@code display_order}, sonra {@code id} sırasıyla. */
    List<RestaurantArea> findActiveAreasOrdered();

    /** Aktif masa yerleşimleri; {@code display_order}, sonra {@code table_no} sırasıyla. */
    List<TableLayoutEntry> findActiveTablesOrdered();
}
