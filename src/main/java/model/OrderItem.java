package model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Maps to DB table: order_items
 * Columns:
 *  id (BaseEntity.id), order_id, product_id, product_name,
 *  quantity, unit_price, net_amount (generated), tax_amount (generated), line_total (generated)
 */
public class OrderItem extends BaseEntity {

    private Long orderId;
    private Long productId;
    private String productName;      // DB: product_name (snapshot)
    private int quantity;            // > 0
    private BigDecimal unitPrice;    // DB: unit_price (NET)

    // DB-generated (STORED) alanlar:
    private BigDecimal netAmount;    // quantity * unit_price
    private BigDecimal taxAmount;    // netAmount * 0.20
    private BigDecimal lineTotal;    // netAmount * 1.20

    /**
     * Garson bu satırı kategorinin varsayılan mutfağı dışında bir mutfağa
     * yönlendirdiyse, yazıcı id'si burada saklanır. Null → kategori default'u.
     * DB sütunu: order_items.kitchen_override_id
     */
    private Integer kitchenOverrideId;

    /**
     * Sipariş anındaki "1 porsiyonda kaç birim" snapshot'ı.
     * Ürün ileride değişse bile sipariş raporu doğru kalsın diye.
     * DB sütunu: order_items.pieces_per_portion (NULL → ürün şiş bazlı değildi)
     */
    private Integer piecesPerPortion;

    /** Sipariş anındaki birim etiketi snapshot'ı (örn. "şiş"). */
    private String unitLabel;

    /**
     * Bu kalem en son hangi tarihte mutfak yazıcısına basıldı?
     * {@code null} → henüz basılmamış (yeni eklenen "ek sipariş" kalemi).
     * DB sütunu: order_items.printed_at
     */
    private LocalDateTime printedAt;

    /**
     * Bu satıra özel not / özelleştirmeler.
     * Örn. "Az pişmiş", "Soğansız, tuzsuz", "Bibersiz, acılı".
     * DB sütunu: order_items.note
     */
    private String note;

    public OrderItem() {}

    /** UI'de satır eklerken pratik ctor. */
    public OrderItem(Long orderId, Long productId, int quantity, Product product) {
        Objects.requireNonNull(product, "product null olamaz");
        setOrderId(orderId);
        setProductId(productId);
        setQuantity(quantity);
        setUnitPrice(product.getUnitPrice());   // snapshot fiyat (uygulamadan da gönderebilirsin)
        setProductName(product.getPName());     // UI gösterimi + insert'te de gönderebilirsin
    }

    // --- getters/setters ---

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity > 0 olmalı");
        this.quantity = quantity;
    }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) {
        if (unitPrice == null || unitPrice.signum() < 0)
            throw new IllegalArgumentException("unitPrice negatif olamaz");
        this.unitPrice = MoneyUtil.two(unitPrice);
    }

    public BigDecimal getNetAmount() { return netAmount; }
    /** DAO map eder (SELECT'te). */
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = MoneyUtil.two(netAmount); }

    public BigDecimal getTaxAmount() { return taxAmount; }
    /** DAO map eder (SELECT'te). */
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = MoneyUtil.two(taxAmount); }

    public BigDecimal getLineTotal() { return lineTotal; }
    /** DAO map eder (SELECT'te). */
    public void setLineTotal(BigDecimal lineTotal) { this.lineTotal = MoneyUtil.two(lineTotal); }

    public Integer getKitchenOverrideId() { return kitchenOverrideId; }
    public void setKitchenOverrideId(Integer id) { this.kitchenOverrideId = id; }

    public Integer getPiecesPerPortion() { return piecesPerPortion; }
    public void setPiecesPerPortion(Integer piecesPerPortion) { this.piecesPerPortion = piecesPerPortion; }

    public String getUnitLabel() { return unitLabel; }
    public void setUnitLabel(String unitLabel) { this.unitLabel = unitLabel; }

    public LocalDateTime getPrintedAt() { return printedAt; }
    public void setPrintedAt(LocalDateTime printedAt) { this.printedAt = printedAt; }

    /** Bu kalem henüz mutfağa düşürülmedi mi? (Yeni eklenen "ek" kalem) */
    public boolean isPending() { return printedAt == null; }

    public String getNote() { return note; }
    public void setNote(String note) {
        if (note == null) { this.note = null; return; }
        String trimmed = note.trim();
        if (trimmed.isEmpty()) { this.note = null; return; }
        if (trimmed.length() > 255) trimmed = trimmed.substring(0, 255);
        this.note = trimmed;
    }

    /** Ürün değiştiyse kolay snapshot güncellemesi (oran yok!). */
    public void updateSnapshotFrom(Product product) {
        Objects.requireNonNull(product, "product null olamaz");
        setProductId(product.getId());
        setUnitPrice(product.getUnitPrice());
        setProductName(product.getPName());
    }

    @Override
    public String toString() {
        return "OrderItem{orderId=" + orderId +
                ", productId=" + productId +
                ", name='" + productName + '\'' +
                ", qty=" + quantity +
                ", unitPrice=" + unitPrice +
                '}';
    }
}
