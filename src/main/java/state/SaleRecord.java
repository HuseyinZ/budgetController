package state;

import model.PaymentMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

public class SaleRecord {
    private final int tableNo;
    private final String building;
    private final String section;
    private final BigDecimal total;
    private final PaymentMethod method;
    private final String performedBy;
    private final LocalDateTime timestamp;

    public SaleRecord(int tableNo, String building, String section, BigDecimal total, PaymentMethod method, String performedBy, LocalDateTime timestamp) {
        this.tableNo = tableNo;
        this.building = building;
        this.section = section;
        this.total = total == null ? BigDecimal.ZERO : total.setScale(2, RoundingMode.HALF_UP);
        this.method = method;
        this.performedBy = performedBy == null ? "" : performedBy;
        this.timestamp = timestamp == null ? LocalDateTime.now() : timestamp;
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

    public BigDecimal getTotal() {
        return total;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}
