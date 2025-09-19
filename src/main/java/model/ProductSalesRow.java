package model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Basit rapor satırı taşıyıcısı.
 */
public class ProductSalesRow {
    private final String productName;
    private final int quantityTotal;
    private final BigDecimal amountTotal;

    public ProductSalesRow(String productName, int quantityTotal, BigDecimal amountTotal) {
        this.productName = productName == null ? "" : productName.trim();
        this.quantityTotal = Math.max(0, quantityTotal);
        this.amountTotal = amountTotal == null ? BigDecimal.ZERO : amountTotal;
    }

    public String getProductName() {
        return productName;
    }

    public int getQuantityTotal() {
        return quantityTotal;
    }

    public BigDecimal getAmountTotal() {
        return amountTotal;
    }

    @Override
    public String toString() {
        return "ProductSalesRow{" +
                "productName='" + productName + '\'' +
                ", quantityTotal=" + quantityTotal +
                ", amountTotal=" + amountTotal +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProductSalesRow that)) return false;
        return quantityTotal == that.quantityTotal
                && Objects.equals(productName, that.productName)
                && Objects.equals(amountTotal, that.amountTotal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productName, quantityTotal, amountTotal);
    }
}
