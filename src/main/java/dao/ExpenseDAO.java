package dao;

import model.Expense;

import java.time.LocalDate;
import java.util.List;

public interface ExpenseDAO extends CrudRepository<Expense, Long> {
    List<Expense> findByDate(LocalDate date);
    List<Expense> findBetween(LocalDate startInclusive, LocalDate endExclusive);
}
