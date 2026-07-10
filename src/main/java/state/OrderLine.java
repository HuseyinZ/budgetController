package state;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public class OrderLine {
    private final String productName;
    private final BigDecimal unitPrice;
    private int quantity;
    /**
     * {@code true} → bu kalem henüz mutfağa basılmadı (yeni eklendi).
     * UI tarafında vurgulu/farklı renkli gösterilir.
     */
    private boolean pending = true;
    /** Bu satıra ait not / özelleştirme (örn. "Soğansız, az pişmiş"). */
    private String note;
    /**
     * DB kimliği: {@code order_items.id} (Stage 1A). DB-backed satırlarda dolu,
     * in-memory/legacy yapımlarda {@code null}. Katalog kimliği olan
     * {@code productId} ile karıştırılmamalıdır.
     */
    private final Long itemId;

    public OrderLine(String productName, BigDecimal unitPrice, int quantity) {
        this(productName, unitPrice, quantity, true, null);
    }

    public OrderLine(String productName, BigDecimal unitPrice, int quantity, boolean pending) {
        this(productName, unitPrice, quantity, pending, null);
    }

    public OrderLine(String productName, BigDecimal unitPrice, int quantity, boolean pending, String note) {
        this(productName, unitPrice, quantity, pending, note, null);
    }

    public OrderLine(String productName, BigDecimal unitPrice, int quantity, boolean pending, String note,
                     Long itemId) {
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity");
        }
        this.productName = productName.trim();
        this.unitPrice = unitPrice.setScale(2, RoundingMode.HALF_UP);
        this.quantity = quantity;
        this.pending = pending;
        this.note = (note == null || note.isBlank()) ? null : note.trim();
        this.itemId = itemId;
    }

    public boolean isPending() { return pending; }
    public void setPending(boolean pending) { this.pending = pending; }

    /** DB kimliği ({@code order_items.id}); in-memory satırlarda {@code null}. */
    public Long getItemId() { return itemId; }

    public String getNote() { return note; }
    public void setNote(String note) {
        this.note = (note == null || note.isBlank()) ? null : note.trim();
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public void increase(int delta) {
        if (delta <= 0) throw new IllegalArgumentException("delta");
        quantity += delta;
    }

    public void decrease(int delta) {
        if (delta <= 0) throw new IllegalArgumentException("delta");
        quantity = Math.max(0, quantity - delta);
    }

    public BigDecimal getLineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public boolean isEmpty() {
        return quantity <= 0;
    }

    public OrderLine copy() {
        return new OrderLine(productName, unitPrice, Math.max(quantity, 0));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderLine)) return false;
        OrderLine orderLine = (OrderLine) o;
        return productName.equalsIgnoreCase(orderLine.productName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productName.toLowerCase());
    }
}
