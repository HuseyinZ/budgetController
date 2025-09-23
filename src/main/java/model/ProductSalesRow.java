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
    private final String categoryName;
    private final int quantity;
    private final PaymentMethod paymentMethod;
    private final BigDecimal amountTotal;

    public ProductSalesRow(LocalDateTime soldAt,
                           String productName,
                           String categoryName,
                           int quantity,
                           PaymentMethod paymentMethod,
                           BigDecimal amountTotal) {
        this.soldAt = soldAt;
        this.productName = productName == null ? "" : productName.trim();
        this.categoryName = categoryName == null ? "" : categoryName.trim();
        this.quantity = Math.max(0, quantity);
        this.paymentMethod = paymentMethod;
        this.amountTotal = amountTotal == null ? BigDecimal.ZERO : amountTotal;
    }

    public LocalDateTime getSoldAt() {
        return soldAt;
    }

    public String getProductName() {
        return productName;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public int getQuantity() {
        return quantity;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public BigDecimal getAmountTotal() {
        return amountTotal;
    }

    @Override
    public String toString() {
        return "ProductSalesRow{" +
                "soldAt=" + soldAt +
                ", productName='" + productName + '\'' +
                ", categoryName='" + categoryName + '\'' +
                ", quantity=" + quantity +
                ", paymentMethod=" + paymentMethod +
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
                && Objects.equals(categoryName, that.categoryName)
                && paymentMethod == that.paymentMethod
                && Objects.equals(amountTotal, that.amountTotal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(soldAt, productName, categoryName, quantity, paymentMethod, amountTotal);
    }
}
