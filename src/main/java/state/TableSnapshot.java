package state;

import java.math.BigDecimal;
import java.util.List;

public class TableSnapshot {
    private final int tableNo;
    private final String building;
    private final String section;
    private final TableOrderStatus status;
    private final Long orderId;
    private final List<OrderLine> lines;
    private final List<OrderLogEntry> history;
    private final BigDecimal total;

    public TableSnapshot(int tableNo,
                         String building,
                         String section,
                         TableOrderStatus status,
                         Long orderId,
                         List<OrderLine> lines,
                         List<OrderLogEntry> history,
                         BigDecimal total) {
        this.tableNo = tableNo;
        this.building = building;
        this.section = section;
        this.status = status;
        this.orderId = orderId;
        this.lines = lines == null ? List.of() : List.copyOf(lines);
        this.history = history == null ? List.of() : List.copyOf(history);
        this.total = total == null ? BigDecimal.ZERO : total;
    }

    public int getTableNo() {
        return tableNo;
    }

    public String getBuilding() {
        return building;
    }

    public String getSection() {
        return section;
    }

    public TableOrderStatus getStatus() {
        return status;
    }

    public Long getOrderId() {
        return orderId;
    }

    public List<OrderLine> getLines() {
        return lines;
    }

    public List<OrderLogEntry> getHistory() {
        return history;
    }

    public BigDecimal getTotal() {
        return total;
    }
}
