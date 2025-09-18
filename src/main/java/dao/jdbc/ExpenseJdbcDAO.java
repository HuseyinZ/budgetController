package dao.jdbc;

import DataConnection.Db;
import dao.ExpenseDAO;
import model.Expense;

import javax.sql.DataSource;
import java.math.BigDecimal;
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
import java.util.List;
import java.util.Optional;

public class ExpenseJdbcDAO implements ExpenseDAO {

    private final DataSource dataSource;
    private final Connection externalConnection;
    private final Object schemaLock = new Object();
    private volatile boolean descriptionColumnMissing;
    private volatile boolean expenseNameColumnMissing;
    private volatile boolean userIdColumnMissing;

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

        try {
            Date expenseDate = rs.getDate("expense_date");
            if (expenseDate != null) {
                expense.setExpenseDate(expenseDate.toLocalDate());
            }
        } catch (SQLException ignore) {
            // optional column
        }

        if (!userIdColumnMissing) {
            try {
                Object user = rs.getObject("user_id");
                if (user instanceof Number number) {
                    expense.setUserId(number.longValue());
                }
            } catch (SQLException ex) {
                handleMissingUserId(ex);
            }
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
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private Date sqlDate(LocalDate date) {
        LocalDate effective = date == null ? LocalDate.now() : date;
        return Date.valueOf(effective);
    }

    private String readDescription(ResultSet rs) throws SQLException {
        if (!descriptionColumnMissing) {
            try {
                return rs.getString("description");
            } catch (SQLException ex) {
                if (!handleMissingDescription(ex)) {
                    throw ex;
                }
            }
        }

        if (!expenseNameColumnMissing) {
            try {
                return rs.getString("expense_name");
            } catch (SQLException ex) {
                if (handleMissingExpenseName(ex)) {
                    return null;
                }
                throw ex;
            }
        }

        return null;
    }

    @Override
    public Long create(Expense expense) {
        Connection connection = null;
        try {
            connection = acquireConnection();
            while (true) {
                InsertPlan plan = buildInsertPlan();
                try (PreparedStatement ps = connection.prepareStatement(plan.sql(), Statement.RETURN_GENERATED_KEYS)) {
                    plan.bind(ps, expense);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            return rs.getLong(1);
                        }
                    }
                    throw new SQLException("No generated key for expenses");
                } catch (SQLException ex) {
                    if (adjustColumnStates(ex)) {
                        continue;
                    }
                    throw new RuntimeException(ex);
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public void update(Expense expense) {
        Connection connection = null;
        try {
            connection = acquireConnection();
            while (true) {
                UpdatePlan plan = buildUpdatePlan();
                try (PreparedStatement ps = connection.prepareStatement(plan.sql())) {
                    int index = plan.bind(ps, expense);
                    ps.setLong(index, expense.getId());
                    ps.executeUpdate();
                    return;
                } catch (SQLException ex) {
                    if (adjustColumnStates(ex)) {
                        continue;
                    }
                    throw new RuntimeException(ex);
                }
            }
        } catch (SQLException ex) {
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
        final String sql =
                "SELECT * FROM expenses WHERE expense_date >= ? AND expense_date < ? ORDER BY expense_date, id";
        List<Expense> out = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
                    System.err.println("Gider tablosunda 'description' sütunu bulunamadı. "
                            + "Açıklamalar kaydedilmeyecek. Ayrıntı: " + ex.getMessage());
                }
            }
        }
        return true;
    }

    private boolean handleMissingExpenseName(SQLException ex) {
        if (!isMissingExpenseName(ex)) {
            return false;
        }
        if (!expenseNameColumnMissing) {
            synchronized (schemaLock) {
                if (!expenseNameColumnMissing) {
                    expenseNameColumnMissing = true;
                    System.err.println("Gider tablosunda 'expense_name' sütunu bulunamadı. "
                            + "Açıklamalar kaydedilmeyecek. Ayrıntı: " + ex.getMessage());
                }
            }
        }
        return true;
    }

    private boolean handleMissingUserId(SQLException ex) {
        if (!isMissingUserId(ex)) {
            return false;
        }
        if (!userIdColumnMissing) {
            synchronized (schemaLock) {
                if (!userIdColumnMissing) {
                    userIdColumnMissing = true;
                    System.err.println("Gider tablosunda 'user_id' sütunu bulunamadı. "
                            + "Kullanıcı bilgisi kaydedilmeyecek. Ayrıntı: " + ex.getMessage());
                }
            }
        }
        return true;
    }

    private boolean isMissingDescription(SQLException ex) {
        return isMissingColumn(ex, "description");
    }

    private boolean isMissingExpenseName(SQLException ex) {
        return isMissingColumn(ex, "expense_name");
    }

    private boolean isMissingUserId(SQLException ex) {
        return isMissingColumn(ex, "user_id");
    }

    private boolean isMissingColumn(SQLException ex, String columnName) {
        SQLException current = ex;
        while (current != null) {
            String state = current.getSQLState();
            if ("42S22".equals(state)) {
                return true;
            }
            if (messageRefersMissingColumn(current.getMessage(), columnName)) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    private boolean messageRefersMissingColumn(String message, String columnName) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("unknown column") && lower.contains(columnName.toLowerCase());
    }

    private boolean adjustColumnStates(SQLException ex) {
        boolean adjusted = false;
        if (handleMissingDescription(ex)) {
            adjusted = true;
        }
        if (handleMissingExpenseName(ex)) {
            adjusted = true;
        }
        if (handleMissingUserId(ex)) {
            adjusted = true;
        }
        return adjusted;
    }

    private String resolvedDescriptionColumn() {
        if (!descriptionColumnMissing) {
            return "description";
        }
        if (!expenseNameColumnMissing) {
            return "expense_name";
        }
        return null;
    }

    private InsertPlan buildInsertPlan() {
        List<ParameterBinder> binders = new ArrayList<>();
        List<String> columns = new ArrayList<>();

        columns.add("amount");
        binders.add((ps, index, expense) -> ps.setBigDecimal(index, safeAmount(expense.getAmount())));

        String descriptionColumn = resolvedDescriptionColumn();
        if (descriptionColumn != null) {
            columns.add(descriptionColumn);
            binders.add((ps, index, expense) -> ps.setString(index, expense.getDescription()));
        }

        columns.add("expense_date");
        binders.add((ps, index, expense) -> ps.setDate(index, sqlDate(expense.getExpenseDate())));

        if (!userIdColumnMissing) {
            columns.add("user_id");
            binders.add((ps, index, expense) -> {
                if (expense.getUserId() == null) {
                    ps.setNull(index, Types.BIGINT);
                } else {
                    ps.setLong(index, expense.getUserId());
                }
            });
        }

        StringBuilder sql = new StringBuilder("INSERT INTO expenses (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(columns.get(i));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        sql.append(")");

        return new InsertPlan(sql.toString(), binders);
    }

    private UpdatePlan buildUpdatePlan() {
        List<ParameterBinder> binders = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE expenses SET ");
        boolean first = true;

        first = appendAssignment(sql, binders, first, "amount",
                (ps, index, expense) -> ps.setBigDecimal(index, safeAmount(expense.getAmount())));

        String descriptionColumn = resolvedDescriptionColumn();
        if (descriptionColumn != null) {
            first = appendAssignment(sql, binders, first, descriptionColumn,
                    (ps, index, expense) -> ps.setString(index, expense.getDescription()));
        }

        first = appendAssignment(sql, binders, first, "expense_date",
                (ps, index, expense) -> ps.setDate(index, sqlDate(expense.getExpenseDate())));

        if (!userIdColumnMissing) {
            first = appendAssignment(sql, binders, first, "user_id", (ps, index, expense) -> {
                if (expense.getUserId() == null) {
                    ps.setNull(index, Types.BIGINT);
                } else {
                    ps.setLong(index, expense.getUserId());
                }
            });
        }

        if (!first) {
            sql.append(", ");
        }
        sql.append("updated_at=NOW() WHERE id=?");

        return new UpdatePlan(sql.toString(), binders);
    }

    private boolean appendAssignment(StringBuilder sql, List<ParameterBinder> binders, boolean first,
                                     String column, ParameterBinder binder) {
        if (!first) {
            sql.append(", ");
        }
        sql.append(column).append("=?");
        binders.add(binder);
        return false;
    }

    private interface ParameterBinder {
        void bind(PreparedStatement ps, int index, Expense expense) throws SQLException;
    }

    private record InsertPlan(String sql, List<ParameterBinder> binders) {
        void bind(PreparedStatement ps, Expense expense) throws SQLException {
            for (int i = 0; i < binders.size(); i++) {
                binders.get(i).bind(ps, i + 1, expense);
            }
        }
    }

    private record UpdatePlan(String sql, List<ParameterBinder> binders) {
        int bind(PreparedStatement ps, Expense expense) throws SQLException {
            int index = 1;
            for (ParameterBinder binder : binders) {
                binder.bind(ps, index++, expense);
            }
            return index;
        }
    }
}
