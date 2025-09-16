package model;

public class RestaurantTable extends BaseEntity {
    private Integer tableNo;         // dining_tables.table_no (unique)
    private TableStatus status = TableStatus.EMPTY;
    private String note;

    public Integer getTableNo() { return tableNo; }
    public void setTableNo(Integer tableNo) { this.tableNo = tableNo; }

    public TableStatus getStatus() { return status; }
    public void setStatus(TableStatus status) { this.status = status; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public boolean isOccupied() { return status == TableStatus.OCCUPIED; }
}
