package state;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

public class TableOrder {
    private final int tableNo;
    private final String building;
    private final String section;
    private TableOrderStatus status = TableOrderStatus.EMPTY;
    private final List<OrderLine> lines = new ArrayList<>();
    private final Deque<OrderLogEntry> history = new ArrayDeque<>();
    private LocalDateTime lastUpdated = LocalDateTime.now();
    private static final int HISTORY_LIMIT = 50;

    public TableOrder(int tableNo, String building, String section) {
        this.tableNo = tableNo;
        this.building = building;
        this.section = section;
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

    public void setStatus(TableOrderStatus status) {
        this.status = status;
        touch();
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public List<OrderLogEntry> getHistory() {
        return List.copyOf(history);
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    public BigDecimal getTotal() {
        return lines.stream()
                .map(OrderLine::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public void addOrIncrementLine(String productName, BigDecimal unitPrice, int quantity) {
        Optional<OrderLine> existing = lines.stream()
                .filter(line -> line.getProductName().equalsIgnoreCase(productName))
                .findFirst();
        if (existing.isPresent()) {
            existing.get().increase(quantity);
        } else {
            lines.add(new OrderLine(productName, unitPrice, quantity));
        }
        touch();
    }

    public boolean decrementLine(String productName, int quantity) {
        Optional<OrderLine> existing = lines.stream()
                .filter(line -> line.getProductName().equalsIgnoreCase(productName))
                .findFirst();
        if (existing.isEmpty()) {
            return false;
        }
        OrderLine line = existing.get();
        line.decrease(quantity);
        if (line.isEmpty()) {
            lines.remove(line);
        }
        touch();
        return true;
    }

    public boolean removeLine(String productName) {
        boolean removed = lines.removeIf(line -> line.getProductName().equalsIgnoreCase(productName));
        if (removed) {
            touch();
        }
        return removed;
    }

    public void clearLines() {
        lines.clear();
        touch();
    }

    public void log(String message) {
        history.addFirst(new OrderLogEntry(LocalDateTime.now(), message));
        while (history.size() > HISTORY_LIMIT) {
            history.removeLast();
        }
    }

    private void touch() {
        lastUpdated = LocalDateTime.now();
    }
}
