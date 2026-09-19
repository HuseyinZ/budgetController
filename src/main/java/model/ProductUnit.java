package model;

import java.time.LocalDateTime;

/**
 * Ürün birimi (porsiyon, şiş, kg …) — kaynağı {@code product_units} tablosudur.
 *
 * <p>Birim listesi V004'ten itibaren kodda hardcoded DEĞİLDİR; UI listeyi
 * daima DB'den okur.
 */
public class ProductUnit {

    private Integer id;
    private String code;
    private String displayName;
    private int displayOrder;
    private boolean active = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** UI'da görünecek metin; {@code display_name} boşsa {@code code}. */
    public String label() {
        return displayName == null || displayName.isBlank() ? code : displayName;
    }

    @Override
    public String toString() {
        return label();
    }
}
