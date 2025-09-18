package dao.jdbc;

import DataConnection.Db;
import dao.ExpenseDAO;
import model.Expense;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ExpenseJdbcDAO implements ExpenseDAO {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExpenseJdbcDAO.class);

    private static final String INSERT_SQL =
            "INSERT INTO expenses (amount, description, expense_date, user_id) VALUES (?,?,?,?)";
    private static final String INSERT_WITHOUT_DESCRIPTION_SQL =
            "INSERT INTO expenses (amount, expense_date, user_id) VALUES (?,?,?)";
    private static final String UPDATE_SQL =
            "UPDATE expenses SET amount=?, description=?, expense_date=?, user_id=?, updated_at=NOW() WHERE id=?";
    private static final String UPDATE_WITHOUT_DESCRIPTION_SQL =
            "UPDATE expenses SET amount=?, expense_date=?, user_id=?, updated_at=NOW() WHERE id=?";
    private static final String DELETE_SQL = "DELETE FROM expenses WHERE id=?";
    private static final String SELECT_BY_ID_SQL =
            "SELECT id, amount, description, expense_date, user_id, created_at, updated_at FROM expenses WHERE id=?";
    private static final String SELECT_ALL_SQL =
            "SELECT id, amount, description, expense_date, user_id, created_at, updated_at "
                    + "FROM expenses ORDER BY expense_date DESC, id DESC LIMIT ? OFFSET ?";
    private static final String SELECT_BY_DATE_SQL =
            "SELECT id, amount, description, expense_date, user_id, created_at, updated_at "
                    + "FROM expenses WHERE expense_date=? ORDER BY id DESC";
    private static final String SELECT_BETWEEN_SQL =
            "SELECT id, amount, description, expense_date, user_id, created_at, updated_at "
                    + "FROM expenses WHERE expense_date >= ? AND expense_date < ? ORDER BY expense_date, id";

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
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
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
        expense.setAmount(normalizeReadAmount(rs.getBigDecimal("amount")));
        expense.setDescription(readDescription(rs));

        try {
            Date expenseDate = rs.getDate("expense_date");
            if (expenseDate != null) {
                expense.setExpenseDate(expenseDate.toLocalDate());
            }
        } catch (SQLException ignore) {
            // optional column
        }

        try {
            Object user = rs.getObject("user_id");
            if (user instanceof Number number) {
                expense.setUserId(number.longValue());
            }
        } catch (SQLException ignore) {
            // optional column
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
            // optional columns
        }
        return expense;
    }

    private BigDecimal safeAmount(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeReadAmount(BigDecimal amount) {
        return amount == null ? null : amount.setScale(2, RoundingMode.HALF_UP);
    }

    private Date sqlDate(LocalDate date) {
        LocalDate effective = date == null ? LocalDate.now() : date;
        return Date.valueOf(effective);
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

        Objects.requireNonNull(expense, "expense");

        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
                ps.setBigDecimal(1, safeAmount(expense.getAmount()));
                bindDescription(ps, 2, expense.getDescription());
                ps.setDate(3, sqlDate(expense.getExpenseDate()));
                bindUser(ps, 4, expense.getUserId());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        long id = rs.getLong(1);
                        expense.setId(id);
                        return id;
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

        Objects.requireNonNull(expense, "expense");
        if (expense.getId() == null) {
            throw new IllegalArgumentException("Expense id is required for update");
        }

        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(UPDATE_SQL)) {
                ps.setBigDecimal(1, safeAmount(expense.getAmount()));
                bindDescription(ps, 2, expense.getDescription());
                ps.setDate(3, sqlDate(expense.getExpenseDate()));
                bindUser(ps, 4, expense.getUserId());
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
            try (PreparedStatement ps = connection.prepareStatement(DELETE_SQL)) {
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
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(SELECT_BY_ID_SQL)) {
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
        if (limit <= 0) {
            return Collections.emptyList();
        }
        int safeOffset = Math.max(0, offset);

        List<Expense> out = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(SELECT_ALL_SQL)) {
                ps.setInt(1, limit);
                ps.setInt(2, safeOffset);
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
        List<Expense> out = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(SELECT_BY_DATE_SQL)) {
                ps.setDate(1, sqlDate(date));
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
        List<Expense> out = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(SELECT_BETWEEN_SQL)) {
                LocalDate start = startInclusive == null ? LocalDate.now() : startInclusive;
                LocalDate end = endExclusive;
                if (end == null || !end.isAfter(start)) {
                    end = start.plusDays(1);
                }
                ps.setDate(1, Date.valueOf(start));
                ps.setDate(2, Date.valueOf(end));
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
                    LOGGER.warn("Gider tablosunda 'description' sütunu bulunamadı. Açıklamalar kaydedilmeyecek. Ayrıntı: {}",
                            ex.getMessage());
                }
            }
        }
        return true;
    }

    private boolean isMissingDescription(SQLException ex) {
        SQLException current = ex;
        while (current != null) {
            String state = current.getSQLState();
            if ("42S22".equals(state) || "42703".equals(state)) {
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
        boolean unknown = lower.contains("unknown column")
                || lower.contains("no such column")
                || lower.contains("column") && lower.contains("does not exist");
        return unknown && lower.contains("description");
    }

    private Long createWithoutDescription(Expense expense) {
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(INSERT_WITHOUT_DESCRIPTION_SQL, Statement.RETURN_GENERATED_KEYS)) {
                ps.setBigDecimal(1, safeAmount(expense.getAmount()));
                ps.setDate(2, sqlDate(expense.getExpenseDate()));
                bindUser(ps, 3, expense.getUserId());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        long id = rs.getLong(1);
                        expense.setId(id);
                        return id;
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
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(UPDATE_WITHOUT_DESCRIPTION_SQL)) {
                ps.setBigDecimal(1, safeAmount(expense.getAmount()));
                ps.setDate(2, sqlDate(expense.getExpenseDate()));
                bindUser(ps, 3, expense.getUserId());
                ps.setLong(4, expense.getId());
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    private void bindDescription(PreparedStatement ps, int index, String description) throws SQLException {
        String normalized = normalizeDescription(description);
        if (normalized == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, normalized);
        }
    }

    private void bindUser(PreparedStatement ps, int index, Long userId) throws SQLException {
        if (userId == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, userId);
        }
    }

    private String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
