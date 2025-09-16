package model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Payment extends BaseEntity {
    private Long orderId;            // FK -> orders.id
    private Long cashierId;          // FK -> users.id
    private BigDecimal amount;       // genelde orders.total
    private PaymentMethod method;    // CASH, CREDIT_CARD, DEBIT_CARD, TRANSFER, ONLINE, MIXED
    private LocalDateTime paidAt;    // DB: CURRENT_TIMESTAMP

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getCashierId() { return cashierId; }
    public void setCashierId(Long cashierId) { this.cashierId = cashierId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = MoneyUtil.two(amount); }

    public PaymentMethod getMethod() { return method; }
    public void setMethod(PaymentMethod method) { this.method = method; }

    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
}
