package model;

/**
 * Bir masanın yerleşim kaydı. Kaynağı {@code restaurant_table_layout} (V004).
 *
 * <p>Koordinat/ölçü alanları ({@code posX}, {@code posY}, {@code width},
 * {@code height}) bu aşamada NULL olabilir: yerleşim editörü henüz yok, ancak
 * model ileride 0-1000 normalize koordinatları taşıyabilsin diye hazır tutulur.
 */
public class TableLayoutEntry {

    private int tableNo;
    private int areaId;
    private Integer posX;
    private Integer posY;
    private Integer width;
    private Integer height;
    private String shape = "RECT";
    private int rotationDeg;
    private int displayOrder;
    private boolean active = true;

    public int getTableNo() { return tableNo; }
    public void setTableNo(int tableNo) { this.tableNo = tableNo; }

    public int getAreaId() { return areaId; }
    public void setAreaId(int areaId) { this.areaId = areaId; }

    public Integer getPosX() { return posX; }
    public void setPosX(Integer posX) { this.posX = posX; }

    public Integer getPosY() { return posY; }
    public void setPosY(Integer posY) { this.posY = posY; }

    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }

    public String getShape() { return shape; }
    public void setShape(String shape) { this.shape = shape == null ? "RECT" : shape; }

    public int getRotationDeg() { return rotationDeg; }
    public void setRotationDeg(int rotationDeg) { this.rotationDeg = rotationDeg; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
