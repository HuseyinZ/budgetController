package model;

import java.util.Objects;

/**
 * DB karşılığı: user_area_permissions
 *
 * <p>Bir garsonun erişebileceği (bina, salon) çiftini tanımlar.
 * Admin/Kasiyer kullanıcıları için bu tablo değerlendirilmez; onlar
 * tüm alanları görür.
 */
public class UserAreaPermission extends BaseEntity {

    private Long userId;
    private String building;     // örn. "1. Bina"
    private String section;      // örn. "2. Kat"

    public UserAreaPermission() {}

    public UserAreaPermission(Long userId, String building, String section) {
        this.userId = userId;
        this.building = building;
        this.section = section;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getBuilding() { return building; }
    public void setBuilding(String building) { this.building = building; }

    public String getSection() { return section; }
    public void setSection(String section) { this.section = section; }

    /** Aynı (building, section) çiftini iki kez listede tutmayalım diye eşitlik anahtarı. */
    public String key() {
        return safe(building) + "||" + safe(section);
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserAreaPermission other)) return false;
        return Objects.equals(userId, other.userId)
                && Objects.equals(safe(building), safe(other.building))
                && Objects.equals(safe(section), safe(other.section));
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, safe(building), safe(section));
    }

    @Override
    public String toString() {
        return "UserAreaPermission{u=" + userId + ", " + building + " / " + section + "}";
    }
}
