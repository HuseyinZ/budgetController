package model;

import java.math.BigDecimal;

public class Product extends BaseEntity {
    protected String pName;
    private Long categoryId;           // basit JDBC için FK id
    private BigDecimal unitPrice;      // NET fiyat (KDV hariç)
    private BigDecimal vatRate;        // örn: 0.20 (yüzde 20)
    private boolean active = true;
    public static final int NAME_MAX = 100;
    public static final int SKU_MAX = 64;
    public static final java.math.BigDecimal DEFAULT_VAT = new java.math.BigDecimal("0.20");

    public String getPName() {
        return pName;
    }

    public void setPName(String pName) {
        if (pName == null || (pName = pName.trim()).isEmpty())
            throw new IllegalArgumentException("name boş olamaz");
        if (pName.length() > NAME_MAX)
            throw new IllegalArgumentException("name uzun");
        this.pName = pName;
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
        if (vatRate.compareTo(java.math.BigDecimal.ZERO) < 0 || vatRate.compareTo(java.math.BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("KDV oranı 0..1 olmalı");
        }
        this.vatRate = vatRate;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

}
