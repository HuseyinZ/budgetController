package state;

import model.PaymentMethod;
import model.User;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class AppState {
    public static final String EVENT_TABLES = "tables";
    public static final String EVENT_SALES = "sales";
    public static final String EVENT_EXPENSES = "expenses";

    public static final class AreaDefinition {
        private final String building;
        private final String section;
        private final int startTableNo;
        private final int tableCount;

        public AreaDefinition(String building, String section, int startTableNo, int tableCount) {
            this.building = building;
            this.section = section;
            this.startTableNo = startTableNo;
            this.tableCount = tableCount;
        }

        public String getBuilding() {
            return building;
        }

        public String getSection() {
            return section;
        }

        public List<Integer> getTableNumbers() {
            return IntStream.range(0, tableCount)
                    .map(i -> startTableNo + i)
                    .boxed()
                    .collect(Collectors.toList());
        }
    }

    private static class Holder {
        private static final AppState INSTANCE = new AppState();
    }

    public static AppState getInstance() {
        return Holder.INSTANCE;
    }

    private final Map<Integer, TableOrder> tableOrders = new LinkedHashMap<>();
    private final List<SaleRecord> sales = new ArrayList<>();
    private final List<ExpenseRecord> expenses = new ArrayList<>();
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final List<AreaDefinition> areas;

    private AppState() {
        this.areas = createDefaultAreas();
        initTables();
    }

    private List<AreaDefinition> createDefaultAreas() {
        List<AreaDefinition> defs = new ArrayList<>();
        defs.add(new AreaDefinition("1. Bina", "1. Kat", 101, 10));
        defs.add(new AreaDefinition("1. Bina", "2. Kat", 111, 10));
        defs.add(new AreaDefinition("1. Bina", "3. Kat", 121, 10));
        defs.add(new AreaDefinition("2. Bina", "1. Kat", 201, 10));
        defs.add(new AreaDefinition("2. Bina", "2. Kat", 211, 10));
        defs.add(new AreaDefinition("2. Bina", "3. Kat", 221, 10));
        defs.add(new AreaDefinition("3. Bina", "Bahçe", 301, 10));
        return Collections.unmodifiableList(defs);
    }

    private void initTables() {
        for (AreaDefinition area : areas) {
            for (Integer tableNo : area.getTableNumbers()) {
                tableOrders.put(tableNo, new TableOrder(tableNo, area.getBuilding(), area.getSection()));
            }
        }
    }

    public List<AreaDefinition> getAreas() {
        return areas;
    }

    public void addPropertyChangeListener(PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(listener);
    }

    private TableOrder requireTable(int tableNo) {
        TableOrder order = tableOrders.get(tableNo);
        if (order == null) {
            throw new IllegalArgumentException("Masa bulunamadı: " + tableNo);
        }
        return order;
    }

    private void notifyTableChanged(int tableNo) {
        pcs.firePropertyChange(EVENT_TABLES, null, tableNo);
    }

    private void notifySalesChanged() {
        pcs.firePropertyChange(EVENT_SALES, null, List.copyOf(sales));
    }

    private void notifyExpensesChanged() {
        pcs.firePropertyChange(EVENT_EXPENSES, null, List.copyOf(expenses));
    }

    private static String actor(User user) {
        if (user == null) {
            return "Sistem";
        }
        String fullName = user.getFullName();
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }
        return Objects.toString(user.getUsername(), "Sistem");
    }

    public synchronized TableSnapshot snapshot(int tableNo) {
        TableOrder order = requireTable(tableNo);
        List<OrderLine> lineCopies = order.getLines().stream()
                .map(line -> new OrderLine(line.getProductName(), line.getUnitPrice(), line.getQuantity()))
                .collect(Collectors.toUnmodifiableList());
        List<OrderLogEntry> historyCopies = order.getHistory().stream()
                .map(entry -> new OrderLogEntry(entry.getTimestamp(), entry.getMessage()))
                .collect(Collectors.toUnmodifiableList());
        return new TableSnapshot(order.getTableNo(), order.getBuilding(), order.getSection(), order.getStatus(), lineCopies, historyCopies, order.getTotal());
    }

    public synchronized BigDecimal getTableTotal(int tableNo) {
        return requireTable(tableNo).getTotal();
    }

    public synchronized TableOrderStatus getTableStatus(int tableNo) {
        return requireTable(tableNo).getStatus();
    }

    public synchronized void addItem(int tableNo, String productName, BigDecimal price, int quantity, User user) {
        if (quantity <= 0) throw new IllegalArgumentException("Adet sıfır olamaz");
        TableOrder order = requireTable(tableNo);
        order.addOrIncrementLine(productName, price, quantity);
        order.setStatus(TableOrderStatus.ORDERED);
        order.log(actor(user) + " " + quantity + " x " + productName + " ekledi");
        notifyTableChanged(tableNo);
    }

    public synchronized void decreaseItem(int tableNo, String productName, int quantity, User user) {
        if (quantity <= 0) throw new IllegalArgumentException("Adet sıfır olamaz");
        TableOrder order = requireTable(tableNo);
        if (order.decrementLine(productName, quantity)) {
            order.log(actor(user) + " " + quantity + " x " + productName + " azalttı");
            if (order.getLines().isEmpty()) {
                order.setStatus(TableOrderStatus.EMPTY);
                order.log("Sipariş temizlendi");
            }
            notifyTableChanged(tableNo);
        }
    }

    public synchronized void removeItem(int tableNo, String productName, User user) {
        TableOrder order = requireTable(tableNo);
        if (order.removeLine(productName)) {
            order.log(actor(user) + " " + productName + " ürününü sildi");
            if (order.getLines().isEmpty()) {
                order.setStatus(TableOrderStatus.EMPTY);
                order.log("Sipariş temizlendi");
            }
            notifyTableChanged(tableNo);
        }
    }

    public synchronized void markServed(int tableNo, User user) {
        TableOrder order = requireTable(tableNo);
        if (order.getLines().isEmpty()) {
            return;
        }
        order.setStatus(TableOrderStatus.SERVED);
        order.log(actor(user) + " siparişi servis etti");
        notifyTableChanged(tableNo);
    }

    public synchronized void clearTable(int tableNo, User user) {
        TableOrder order = requireTable(tableNo);
        order.clearLines();
        order.setStatus(TableOrderStatus.EMPTY);
        order.log(actor(user) + " masayı temizledi");
        notifyTableChanged(tableNo);
    }

    public synchronized void recordSale(int tableNo, PaymentMethod method, User user) {
        TableOrder order = requireTable(tableNo);
        BigDecimal total = order.getTotal();
        SaleRecord record = new SaleRecord(tableNo, order.getBuilding(), order.getSection(), total, method, actor(user), LocalDateTime.now());
        sales.add(record);
        order.log(actor(user) + " satış yaptı. Tutar: " + formatCurrency(total) + ", Yöntem: " + (method == null ? "Belirtilmedi" : method.name()));
        order.clearLines();
        order.setStatus(TableOrderStatus.EMPTY);
        notifyTableChanged(tableNo);
        notifySalesChanged();
    }

    public synchronized List<SaleRecord> getSalesOn(LocalDate date) {
        return sales.stream()
                .filter(sale -> sale.getTimestamp().toLocalDate().equals(date))
                .collect(Collectors.toUnmodifiableList());
    }

    public synchronized List<SaleRecord> getSales() {
        return List.copyOf(sales);
    }

    public synchronized BigDecimal getSalesTotal(LocalDate date) {
        return sumAmounts(getSalesOn(date).stream().map(SaleRecord::getTotal).collect(Collectors.toList()));
    }

    public synchronized BigDecimal getSalesTotal(YearMonth yearMonth) {
        return sales.stream()
                .filter(sale -> YearMonth.from(sale.getTimestamp()).equals(yearMonth))
                .map(SaleRecord::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public synchronized void addExpense(BigDecimal amount, String description, LocalDate date, User user) {
        ExpenseRecord record = new ExpenseRecord(amount, description, actor(user), date, LocalDateTime.now());
        expenses.add(record);
        notifyExpensesChanged();
    }

    public synchronized List<ExpenseRecord> getExpensesOn(LocalDate date) {
        return expenses.stream()
                .filter(expense -> expense.getExpenseDate().equals(date))
                .collect(Collectors.toUnmodifiableList());
    }

    public synchronized List<ExpenseRecord> getExpenses() {
        return List.copyOf(expenses);
    }

    public synchronized BigDecimal getExpenseTotal(LocalDate date) {
        return sumAmounts(getExpensesOn(date).stream().map(ExpenseRecord::getAmount).collect(Collectors.toList()));
    }

    public synchronized BigDecimal getExpenseTotal(YearMonth yearMonth) {
        return expenses.stream()
                .filter(expense -> YearMonth.from(expense.getExpenseDate()).equals(yearMonth))
                .map(ExpenseRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public synchronized BigDecimal getNetProfit(LocalDate date) {
        return getSalesTotal(date).subtract(getExpenseTotal(date)).setScale(2, RoundingMode.HALF_UP);
    }

    public synchronized BigDecimal getNetProfit(YearMonth yearMonth) {
        return getSalesTotal(yearMonth).subtract(getExpenseTotal(yearMonth)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sumAmounts(List<BigDecimal> amounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            if (amount != null) {
                total = total.add(amount);
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private String formatCurrency(BigDecimal value) {
        if (value == null) return "0.00";
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
