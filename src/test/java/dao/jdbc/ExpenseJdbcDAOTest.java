package dao.jdbc;

import model.Expense;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpenseJdbcDAOTest {

    private DataSource dataSource;
    private ExpenseJdbcDAO dao;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:expenseTests;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        this.dataSource = ds;

        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS expenses");
            stmt.execute("CREATE TABLE expenses (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "amount DECIMAL(19,2) NOT NULL," +
                    "expense_name VARCHAR(255)," +
                    "expense_date DATE," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
        }

        this.dao = new ExpenseJdbcDAO(dataSource);
    }

    @Test
    void createAndFindFallbacksToExpenseNameColumn() throws SQLException {
        Expense expense = new Expense();
        expense.setAmount(new BigDecimal("42.50"));
        expense.setDescription("Kira");
        expense.setExpenseDate(LocalDate.of(2024, 1, 15));

        Long id = dao.create(expense);
        assertNotNull(id);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT expense_name FROM expenses WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Kira", rs.getString("expense_name"));
            }
        }

        Expense persisted = dao.findById(id).orElseThrow();
        assertEquals("Kira", persisted.getDescription());
    }

    @Test
    void updateWritesIntoExpenseNameColumn() throws SQLException {
        Expense expense = new Expense();
        expense.setAmount(new BigDecimal("12.30"));
        expense.setDescription("Market");
        expense.setExpenseDate(LocalDate.of(2024, 2, 10));

        Long id = dao.create(expense);

        Expense persisted = dao.findById(id).orElseThrow();
        persisted.setDescription("Market Güncelleme");
        persisted.setAmount(new BigDecimal("25.00"));
        dao.update(persisted);

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT expense_name, amount FROM expenses WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Market Güncelleme", rs.getString("expense_name"));
                assertEquals(0, rs.getBigDecimal("amount").compareTo(new BigDecimal("25.00")));
            }
        }

        Expense updated = dao.findById(id).orElseThrow();
        assertEquals("Market Güncelleme", updated.getDescription());
        assertEquals(0, updated.getAmount().compareTo(new BigDecimal("25.00")));
    }
}
