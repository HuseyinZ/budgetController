package service;

import dao.ProductUnitDAO;
import dao.jdbc.ProductUnitJdbcDAO;
import model.ProductUnit;

import java.util.List;

/**
 * Ürün birimleri servisi — DB tek kaynaktır.
 *
 * <p>Bilinçli olarak hardcoded fallback YOKTUR: okuma başarısız olursa hata
 * yüzeye çıkar. Sessizce koda gömülü bir listeye düşmek, DB'yi kaynak yapma
 * amacını ortadan kaldırır ve kullanıcının yanlış birimle ürün kaydetmesine
 * yol açardı.
 */
public class ProductUnitService {

    private final ProductUnitDAO dao;

    public ProductUnitService() {
        this(new ProductUnitJdbcDAO());
    }

    public ProductUnitService(ProductUnitDAO dao) {
        this.dao = dao;
    }

    /** Aktif birimler, {@code display_order} sonra ad sırasıyla. */
    public List<ProductUnit> getActiveUnits() {
        return List.copyOf(dao.findActiveOrdered());
    }
}
