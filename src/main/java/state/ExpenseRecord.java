package state;

import model.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class ExpenseRecord {
    private final Long id;
    private final BigDecimal amount;
    private final String description;
    private final String performedBy;
    private final LocalDate expenseDate;
    private final LocalDateTime createdAt;

    public ExpenseRecord(Long id, BigDecimal amount, String description, String performedBy, LocalDate expenseDate, LocalDateTime createdAt) {
        this.id = id;
        this.amount = MoneyUtil.twoOrUnscaledZero(amount);
        this.description = description == null ? "" : description;
        this.performedBy = performedBy == null ? "" : performedBy;
        this.expenseDate = expenseDate == null ? LocalDate.now() : expenseDate;
        this.createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDescription() {
        return description;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public LocalDate getExpenseDate() {
        return expenseDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
