package state;

import java.math.BigDecimal;
import java.util.List;

public class TableSnapshot {
    private final int tableNo;
    private final String building;
    private final String section;
    private final TableOrderStatus status;
    private final List<OrderLine> lines;
    private final List<OrderLogEntry> history;
    private final BigDecimal total;

    public TableSnapshot(int tableNo, String building, String section, TableOrderStatus status, List<OrderLine> lines, List<OrderLogEntry> history, BigDecimal total) {
        this.tableNo = tableNo;
        this.building = building;
        this.section = section;
        this.status = status;
        this.lines = lines;
        this.history = history;
        this.total = total;
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
