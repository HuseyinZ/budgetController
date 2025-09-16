package model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Order extends BaseEntity {

    private LocalDateTime orderDate;     // DB: orders.order_date (DEFAULT CURRENT_TIMESTAMP)
    private Long tableId;                // FK -> dining_tables.id (nullable)
    private Long waiterId;               // FK -> users.id
    private String note;                 // nullable

    private OrderStatus status = OrderStatus.PENDING;

    // Totals (DB günceller)
    private BigDecimal subtotal = BigDecimal.ZERO;
    private BigDecimal taxTotal = BigDecimal.ZERO;
    private BigDecimal discountTotal = BigDecimal.ZERO;
    private BigDecimal total = BigDecimal.ZERO;

    private LocalDateTime closedAt;      // nullable

    private final List<OrderItem> items = new ArrayList<>();

    public Order(Long tableId, Long waiterId, OrderStatus status) {
        this.tableId = tableId;
        this.waiterId = waiterId;
        this.status = (status != null) ? status : OrderStatus.PENDING; // <-- düzeltildi
        this.orderDate = LocalDateTime.now();
    }

    public LocalDateTime getOrderDate() { return orderDate; }
    public void setOrderDate(LocalDateTime orderDate) { this.orderDate = orderDate; }

    public Long getTableId() { return tableId; }
    public void setTableId(Long tableId) { this.tableId = tableId; }

    public Long getWaiterId() { return waiterId; }
    public void setWaiterId(Long waiterId) { this.waiterId = waiterId; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = MoneyUtil.two(subtotal); }

    public BigDecimal getTaxTotal() { return taxTotal; }
    public void setTaxTotal(BigDecimal taxTotal) { this.taxTotal = MoneyUtil.two(taxTotal); }

    public BigDecimal getDiscountTotal() { return discountTotal; }
    public void setDiscountTotal(BigDecimal discountTotal) { this.discountTotal = MoneyUtil.two(discountTotal); }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = MoneyUtil.two(total); }

    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }

    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }
    public void addItem(OrderItem item) { this.items.add(item); }
    public void clearItems() { this.items.clear(); }
}
