package dao;

import model.ProductUnit;

import java.util.List;

/**
 * Ürün birimleri — bu aşamada YALNIZ OKUMA. Birim yönetimi (ekle/düzenle)
 * ayrı bir iş kalemidir; seed ve özel değer aktarımı migration ile yapılır.
 */
public interface ProductUnitDAO {

    /** Aktif birimler; {@code display_order}, sonra {@code display_name} sırasıyla. */
    List<ProductUnit> findActiveOrdered();
}
