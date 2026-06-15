package model;

import java.math.BigDecimal;
import java.time.LocalDate;

public class Expense extends BaseEntity {
    private BigDecimal amount;
    private String description;
    private LocalDate expenseDate;
    private Long userId;
    /** Kg-bazlı giriş: kilo (null → manuel giriş). */
    private BigDecimal quantityKg;
    /** Kg-bazlı giriş: 1 kg fiyatı (null → manuel giriş). */
    private BigDecimal unitPricePerKg;

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getExpenseDate() {
        return expenseDate;
    }

    public void setExpenseDate(LocalDate expenseDate) {
        this.expenseDate = expenseDate;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getQuantityKg() {
        return quantityKg;
    }

    public void setQuantityKg(BigDecimal quantityKg) {
        this.quantityKg = quantityKg;
    }

    public BigDecimal getUnitPricePerKg() {
        return unitPricePerKg;
    }

    public void setUnitPricePerKg(BigDecimal unitPricePerKg) {
        this.unitPricePerKg = unitPricePerKg;
    }

    public boolean isKgBased() {
        return quantityKg != null && unitPricePerKg != null;
    }
}
