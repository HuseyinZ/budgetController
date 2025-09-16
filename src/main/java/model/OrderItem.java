package model;

import java.math.BigDecimal;
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
