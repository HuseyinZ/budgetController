package service;

import dao.ExpenseDAO;
import dao.jdbc.ExpenseJdbcDAO;
import model.Expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

public class ExpenseService {

    private final ExpenseDAO expenseDAO;

    public ExpenseService() {
        this(new ExpenseJdbcDAO());
    }

    public ExpenseService(ExpenseDAO expenseDAO) {
        this.expenseDAO = Objects.requireNonNull(expenseDAO, "expenseDAO");
    }

    public Long createExpense(Expense expense) {
        return expenseDAO.create(expense);
    }

    public List<Expense> getExpensesOn(LocalDate date) {
        return expenseDAO.findByDate(date);
    }

    public List<Expense> getExpensesBetween(LocalDate startInclusive, LocalDate endExclusive) {
        return expenseDAO.findBetween(startInclusive, endExclusive);
    }

    public List<Expense> getExpensesInMonth(YearMonth month) {
        LocalDate start = month.atDay(1);
        return expenseDAO.findBetween(start, start.plusMonths(1));
    }

    public List<Expense> getAllExpenses() {
        return expenseDAO.findAll(0, Integer.MAX_VALUE);
    }

    public BigDecimal sumExpensesOn(LocalDate date) {
        return sum(expenseDAO.findByDate(date));
    }

    public BigDecimal sumExpensesBetween(LocalDate startInclusive, LocalDate endExclusive) {
        return sum(expenseDAO.findBetween(startInclusive, endExclusive));
    }

    public BigDecimal sumExpensesInMonth(YearMonth month) {
        LocalDate start = month.atDay(1);
        return sum(expenseDAO.findBetween(start, start.plusMonths(1)));
    }

    private BigDecimal sum(List<Expense> expenses) {
        BigDecimal total = BigDecimal.ZERO;
        for (Expense expense : expenses) {
            if (expense.getAmount() != null) {
                total = total.add(expense.getAmount());
            }
        }
        return total;
    }
}
