package model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class Product extends BaseEntity {
    private String name;
    private Long categoryId;           // basit JDBC için FK id
    private BigDecimal unitPrice;      // NET fiyat (KDV hariç) — bir PORSİYON fiyatı
    private BigDecimal vatRate;        // örn: 0.20 (yüzde 20)
    private Integer stock = 0;
    private boolean active = true;
    /**
     * 1 porsiyonda kaç birim (örn. şiş) var?
     * <ul>
     *   <li>{@code null} → ürün porsiyon bazlı; quantity = porsiyon sayısı</li>
     *   <li>{@code >=1} → 1 porsiyon = N şiş (ör. ciğer=4, adana=2)</li>
     * </ul>
     */
    private Integer piecesPerPortion;
    /** Birim etiketi: "porsiyon", "şiş", "adet", "kg" vb. — UI gösterimi içindir. */
    private String unitLabel;
    public static final int NAME_MAX = 100;
    public static final int SKU_MAX = 64;
    public static final BigDecimal DEFAULT_VAT = new BigDecimal("0.20");

    public Product() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name == null || (name = name.trim()).isEmpty()) {
            throw new IllegalArgumentException("name boş olamaz");
        }
        if (name.length() > NAME_MAX) {
            throw new IllegalArgumentException("name uzun");
        }
        this.name = name;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        if (unitPrice == null || unitPrice.signum() < 0) throw new IllegalArgumentException("negatif fiyat");
        this.unitPrice = MoneyUtil.two(unitPrice);
    }

    public BigDecimal getVatRate() {
        return vatRate;
    }

    public void setVatRate(BigDecimal vatRate) {
        if (vatRate == null) {
            this.vatRate = DEFAULT_VAT;
            return;
        }
        if (vatRate.compareTo(BigDecimal.ZERO) < 0 || vatRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("KDV oranı 0..1 olmalı");
        }
        this.vatRate = vatRate;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        if (stock != null && stock < 0) {
            throw new IllegalArgumentException("stok negatif olamaz");
        }
        this.stock = stock;
    }

    public void adjustStock(int delta) {
        int current = stock == null ? 0 : stock;
        int updated = current + delta;
        if (updated < 0) {
            throw new IllegalArgumentException("stok negatif olamaz");
        }
        this.stock = updated;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Integer getPiecesPerPortion() {
        return piecesPerPortion;
    }

    public void setPiecesPerPortion(Integer piecesPerPortion) {
        if (piecesPerPortion != null && piecesPerPortion <= 0) {
            throw new IllegalArgumentException("Porsiyondaki birim sayısı 1 veya üzeri olmalı");
        }
        this.piecesPerPortion = piecesPerPortion;
    }

    public String getUnitLabel() {
        return unitLabel;
    }

    public void setUnitLabel(String unitLabel) {
        if (unitLabel == null) {
            this.unitLabel = null;
            return;
        }
        String trimmed = unitLabel.trim();
        this.unitLabel = trimmed.isEmpty() ? null : trimmed;
    }

    /** True → bu ürün şiş/birim bazlı (1 porsiyon = N birim) fiyatlandırılır. */
    public boolean isPieceBased() {
        return piecesPerPortion != null && piecesPerPortion > 0;
    }

    /**
     * Şiş bazlı fiyatlandırma için "1 birim (şiş)" fiyatını döndürür.
     * Ürün porsiyon bazlıysa unitPrice ile aynı.
     */
    public BigDecimal getPerPiecePrice() {
        if (!isPieceBased() || unitPrice == null) {
            return unitPrice;
        }
        return MoneyUtil.two(unitPrice.divide(
                new BigDecimal(piecesPerPortion),
                4, RoundingMode.HALF_UP));
    }
}
