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
    /** Sipariş-anı snapshot: 1 porsiyondaki birim (şiş) sayısı. {@code null} → porsiyon bazlı. */
    private final Integer piecesPerPortion;
    /** Sipariş-anı snapshot: birim etiketi ("şiş", "porsiyon", ...). Nullable. */
    private final String unitLabel;

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
        this(productName, unitPrice, quantity, pending, note, itemId, null, null);
    }

    public OrderLine(String productName, BigDecimal unitPrice, int quantity, boolean pending, String note,
                     Long itemId, Integer piecesPerPortion, String unitLabel) {
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
        this.piecesPerPortion = piecesPerPortion;
        this.unitLabel = (unitLabel == null || unitLabel.isBlank()) ? null : unitLabel.trim();
    }

    public boolean isPending() { return pending; }
    public void setPending(boolean pending) { this.pending = pending; }

    /** DB kimliği ({@code order_items.id}); in-memory satırlarda {@code null}. */
    public Long getItemId() { return itemId; }

    public Integer getPiecesPerPortion() { return piecesPerPortion; }

    public String getUnitLabel() { return unitLabel; }

    /**
     * Kullanıcıya gösterilecek adet metni (Swing "Adet" hücresi + PWA quantity text).
     *
     * <ul>
     *   <li>Şiş bazlı ({@code piecesPerPortion > 0}): {@code "6 şiş (3 porsiyon)"} —
     *       porsiyon = quantity / piecesPerPortion; en çok 2 ondalık, HALF_UP,
     *       trailing zero'suz, Türkçe virgül.</li>
     *   <li>Porsiyon bazlı + unitLabel dolu: {@code "2 porsiyon"}.</li>
     *   <li>Aksi hâlde mevcut görünüm: {@code "2"}.</li>
     * </ul>
     */
    public String getQuantityLabel() {
        if (piecesPerPortion != null && piecesPerPortion > 0) {
            String unit = (unitLabel == null || unitLabel.isBlank()) ? "şiş" : unitLabel;
            return quantity + " " + unit + " (" + formatPortions() + " porsiyon)";
        }
        if (unitLabel != null && !unitLabel.isBlank()) {
            return quantity + " " + unitLabel;
        }
        return String.valueOf(quantity);
    }

    private String formatPortions() {
        return BigDecimal.valueOf(quantity)
                .divide(BigDecimal.valueOf(piecesPerPortion), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()      // stripTrailingZeros'un E-notasyonuna karşı
                .replace('.', ',');
    }

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
