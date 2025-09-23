package model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Basit rapor satırı taşıyıcısı.
 */
public class ProductSalesRow {
    private final LocalDateTime soldAt;
    private final String productName;
    private final int quantity;
    private final BigDecimal amountTotal;

    public ProductSalesRow(LocalDateTime soldAt, String productName, int quantity, BigDecimal amountTotal) {
        this.soldAt = soldAt;
        this.productName = productName == null ? "" : productName.trim();
        this.quantity = Math.max(0, quantity);
        this.amountTotal = amountTotal == null ? BigDecimal.ZERO : amountTotal;
    }

    public LocalDateTime getSoldAt() {
        return soldAt;
    }

    public String getProductName() {
        return productName;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getAmountTotal() {
        return amountTotal;
    }

    @Override
    public String toString() {
        return "ProductSalesRow{" +
                "soldAt=" + soldAt +
                ", productName='" + productName + '\'' +
                ", quantity=" + quantity +
                ", amountTotal=" + amountTotal +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductSalesRow that)) return false;
        return quantity == that.quantity
                && Objects.equals(soldAt, that.soldAt)
                && Objects.equals(productName, that.productName)
                && Objects.equals(amountTotal, that.amountTotal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(soldAt, productName, quantity, amountTotal);
    }
}
