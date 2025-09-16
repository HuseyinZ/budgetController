package model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * View-Model for "All Sales" screen (NOT a DB table).
 * One instance corresponds to a single row from payments + orders (+ order_items).
 */
public class Sale {

    private Long paymentId;            // payments.id
    private Long orderId;              // orders.id
    private Long cashierId;            // payments.cashier_id
    private LocalDateTime paidAt;      // payments.paid_at

    // orders / order_items üzerinden hesaplanan toplamlar
    private BigDecimal subtotal;       // SUM(oi.net_amount)
    private BigDecimal taxTotal;       // SUM(oi.tax_amount)
    private BigDecimal total;          // SUM(oi.line_total)

    public static class Line {
        public Long productId;
        public String productName;     // oi.product_name (snapshot)
        public int quantity;           // oi.quantity
        public BigDecimal unitPrice;   // oi.unit_price (NET)
        public BigDecimal lineNet;     // oi.net_amount
    }

    private List<Line> lines;

    // --- getters/setters ---

    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getCashierId() { return cashierId; }
    public void setCashierId(Long cashierId) { this.cashierId = cashierId; }

    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = MoneyUtil.two(subtotal); }

    public BigDecimal getTaxTotal() { return taxTotal; }
    public void setTaxTotal(BigDecimal taxTotal) { this.taxTotal = MoneyUtil.two(taxTotal); }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = MoneyUtil.two(total); }

    public List<Line> getLines() { return lines; }
    public void setLines(List<Line> lines) { this.lines = lines; }
}
