package dao.jdbc;

import DataConnection.Db;
import dao.ExpenseDAO;
import model.Expense;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ExpenseJdbcDAO implements ExpenseDAO {

    private final DataSource dataSource;
    private final Connection externalConnection;
    private final Object schemaLock = new Object();
    private volatile boolean descriptionColumnMissing;

    public ExpenseJdbcDAO() {
        this(Db.getDataSource(), null);
    }

    public ExpenseJdbcDAO(DataSource dataSource) {
        this(dataSource, null);
    }

    public ExpenseJdbcDAO(Connection connection) {
        this(Db.getDataSource(), connection);
    }

    private ExpenseJdbcDAO(DataSource dataSource, Connection externalConnection) {
        this.dataSource = dataSource;
        this.externalConnection = externalConnection;
    }

    private Connection acquireConnection() throws SQLException {
        if (externalConnection != null) {
            return externalConnection;
        }
        if (dataSource == null) {
            throw new IllegalStateException("No DataSource configured for ExpenseJdbcDAO");
        }
        return dataSource.getConnection();
    }

    private void close(Connection connection) {
        if (externalConnection == null && connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Expense map(ResultSet rs) throws SQLException {
        Expense expense = new Expense();
        expense.setId(rs.getLong("id"));
        expense.setAmount(rs.getBigDecimal("amount"));
        expense.setDescription(readDescription(rs));

        expense.setDescription(rs.getString("description"));

        java.sql.Date expenseDate = rs.getDate("expense_date");
        if (expenseDate != null) {
            expense.setExpenseDate(expenseDate.toLocalDate());
        }
        Object user = null;
        try {
            user = rs.getObject("user_id");
        } catch (SQLException ignore) {
        }
        if (user != null) {
            expense.setUserId(((Number) user).longValue());
        }
        try {
            Timestamp created = rs.getTimestamp("created_at");
            Timestamp updated = rs.getTimestamp("updated_at");
            if (created != null) {
                expense.setCreatedAt(created.toLocalDateTime());
            }
            if (updated != null) {
                expense.setUpdatedAt(updated.toLocalDateTime());
            }
        } catch (SQLException ignore) {
        }
        return expense;
    }

    private BigDecimal safeAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private java.sql.Date sqlDate(LocalDate date) {
        LocalDate effective = date == null ? LocalDate.now() : date;
        return java.sql.Date.valueOf(effective);
    }

    private String readDescription(ResultSet rs) throws SQLException {
        if (descriptionColumnMissing) {
            return null;
        }
        try {
            return rs.getString("description");
        } catch (SQLException ex) {
            if (handleMissingDescription(ex)) {
                return null;
            }
            throw ex;
        }
    }

    @Override
    public Long create(Expense expense) {
        if (descriptionColumnMissing) {
            return createWithoutDescription(expense);
        }

    @Override
    public Long create(Expense expense) {

        final String sql = "INSERT INTO expenses (amount, description, expense_date, user_id) VALUES (?,?,?,?)";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setBigDecimal(1, safeAmount(expense.getAmount()));
                ps.setString(2, expense.getDescription());
                ps.setDate(3, sqlDate(expense.getExpenseDate()));

                ps.setBigDecimal(1, expense.getAmount() == null ? BigDecimal.ZERO : expense.getAmount());
                ps.setString(2, expense.getDescription());
                ps.setDate(3, java.sql.Date.valueOf(expense.getExpenseDate()));
                if (expense.getUserId() == null) {
                    ps.setNull(4, Types.BIGINT);
                } else {
                    ps.setLong(4, expense.getUserId());
                }
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
                throw new SQLException("No generated key for expenses");
            }
        } catch (SQLException ex) {
            if (handleMissingDescription(ex)) {
                return createWithoutDescription(expense);
            }
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public void update(Expense expense) {
        if (descriptionColumnMissing) {
            updateWithoutDescription(expense);
            return;
        }
        final String sql =
                "UPDATE expenses SET amount=?, description=?, expense_date=?, user_id=?, updated_at=NOW() WHERE id=?";

        final String sql = "UPDATE expenses SET amount=?, description=?, expense_date=?, user_id=?, updated_at=NOW() WHERE id=?";

        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setBigDecimal(1, safeAmount(expense.getAmount()));
                ps.setString(2, expense.getDescription());
                ps.setDate(3, sqlDate(expense.getExpenseDate()));

                ps.setBigDecimal(1, expense.getAmount() == null ? BigDecimal.ZERO : expense.getAmount());
                ps.setString(2, expense.getDescription());
                ps.setDate(3, java.sql.Date.valueOf(expense.getExpenseDate()));

                if (expense.getUserId() == null) {
                    ps.setNull(4, Types.BIGINT);
                } else {
                    ps.setLong(4, expense.getUserId());
                }
                ps.setLong(5, expense.getId());
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            if (handleMissingDescription(ex)) {
                updateWithoutDescription(expense);
                return;
            }
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public void deleteById(Long id) {
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM expenses WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public Optional<Expense> findById(Long id) {
        final String sql = "SELECT * FROM expenses WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public List<Expense> findAll(int offset, int limit) {
        final String sql = "SELECT * FROM expenses ORDER BY expense_date DESC, id DESC LIMIT ? OFFSET ?";
        List<Expense> out = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
        return out;
    }

    @Override
    public List<Expense> findByDate(LocalDate date) {
        final String sql = "SELECT * FROM expenses WHERE expense_date=? ORDER BY id DESC";
        List<Expense> out = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setDate(1, java.sql.Date.valueOf(date));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
        return out;
    }

    @Override
    public List<Expense> findBetween(LocalDate startInclusive, LocalDate endExclusive) {
        final String sql = "SELECT * FROM expenses WHERE expense_date >= ? AND expense_date < ? ORDER BY expense_date, id";
        List<Expense> out = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setDate(1, java.sql.Date.valueOf(startInclusive));
                ps.setDate(2, java.sql.Date.valueOf(endExclusive));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
        return out;
    }

    private boolean handleMissingDescription(SQLException ex) {
        if (!isMissingDescription(ex)) {
            return false;
        }
        if (!descriptionColumnMissing) {
            synchronized (schemaLock) {
                if (!descriptionColumnMissing) {
                    descriptionColumnMissing = true;

                    String message = "Gider tablosunda 'description' sütunu bulunamadı. "
                            + "Açıklamalar kaydedilmeyecek. Ayrıntı: " + ex.getMessage();
                    System.err.println(message);
                    System.err.println("Gider tablosunda 'description' sütunu bulunamadı. Açıklamalar kaydedilmeyecek. Ayrıntı: "
                            + ex.getMessage());
                }
            }
        }
        return true;
    }

    private boolean isMissingDescription(SQLException ex) {
        SQLException current = ex;
        while (current != null) {
            String state = current.getSQLState();
            if ("42S22".equals(state)) {
                return true;
            }
            if (messageRefersMissingDescription(current.getMessage())) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    private boolean messageRefersMissingDescription(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("unknown column") && lower.contains("description");
    }

    private Long createWithoutDescription(Expense expense) {
        final String sql = "INSERT INTO expenses (amount, expense_date, user_id) VALUES (?,?,?)";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setBigDecimal(1, safeAmount(expense.getAmount()));
                ps.setDate(2, sqlDate(expense.getExpenseDate()));
                if (expense.getUserId() == null) {
                    ps.setNull(3, Types.BIGINT);
                } else {
                    ps.setLong(3, expense.getUserId());
                }
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
                throw new SQLException("No generated key for expenses");
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    private void updateWithoutDescription(Expense expense) {
        final String sql = "UPDATE expenses SET amount=?, expense_date=?, user_id=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setBigDecimal(1, safeAmount(expense.getAmount()));
                ps.setDate(2, sqlDate(expense.getExpenseDate()));
                if (expense.getUserId() == null) {
                    ps.setNull(3, Types.BIGINT);
                } else {
                    ps.setLong(3, expense.getUserId());
                }
                ps.setLong(4, expense.getId());
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }


}
