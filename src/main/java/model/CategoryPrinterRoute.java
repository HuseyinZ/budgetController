package model;

/**
 * Veritabanı tablo karşılığı: category_printer_routes
 *
 * <p>Hangi kategori hangi mutfak yazıcısına düşecek? bunun haritasıdır.
 * Bir kategori birden fazla yazıcıya da düşebilir (çoklu kayıt).
 */
public class CategoryPrinterRoute extends BaseEntity {

    private Long categoryId;
    private Integer printerId;

    public CategoryPrinterRoute() {}

    public CategoryPrinterRoute(Long categoryId, Integer printerId) {
        this.categoryId = categoryId;
        this.printerId = printerId;
    }

    public Long getCategoryId()                  { return categoryId; }
    public void setCategoryId(Long categoryId)   { this.categoryId = categoryId; }

    public Integer getPrinterId()                { return printerId; }
    public void setPrinterId(Integer printerId)  { this.printerId = printerId; }

    @Override
    public String toString() {
        return "Route{cat=" + categoryId + " -> printer=" + printerId + "}";
    }
}
