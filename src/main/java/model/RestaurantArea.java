package model;

/**
 * Fiziksel alan: Bina → Kat → Salon. Kaynağı {@code restaurant_areas} (V004).
 *
 * <p>Salt okuma modeli; bu aşamada uygulama alan yazmaz.
 */
public class RestaurantArea {

    private Integer id;
    private String building = "";
    private String floor = "";
    private String salon = "";
    private int displayOrder;
    private boolean active = true;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getBuilding() { return building; }
    public void setBuilding(String building) { this.building = building == null ? "" : building; }

    /** "Kat" karşılığı (örn. "1. Kat", "Bahçe"). */
    public String getFloor() { return floor; }
    public void setFloor(String floor) { this.floor = floor == null ? "" : floor; }

    /** Opsiyonel salon adı; boş string → kat tek salonlu. */
    public String getSalon() { return salon; }
    public void setSalon(String salon) { this.salon = salon == null ? "" : salon; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
