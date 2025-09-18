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
    private volatile boolean noteColumnMissing;
    private volatile boolean userIdColumnMissing;
    private volatile boolean createdByColumnMissing;

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

        Long userId = readUserId(rs);
        if (userId != null) {
            expense.setUserId(userId);
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

    private String prepareExpenseName(String description) {
        String value = description == null ? "" : description.trim();
        if (value.isEmpty()) {
            value = "Gider";
        }
        if (value.length() > 150) {
            value = value.substring(0, 150);
        }
        return value;
    }

    private String prepareExpenseNote(String description) {
        if (description == null) {
            return null;
        }
        String value = description.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > 255) {
            value = value.substring(0, 255);
        }
        return value;
    }

    private String readDescription(ResultSet rs) throws SQLException {
        String column = resolvedExpenseNameColumn();
        if (column != null) {
            try {
                String value = rs.getString(column);
                if (value != null) {
                    return value;
                }
            } catch (SQLException ex) {
                if (!handleMissingDescriptionColumn(column, ex)) {
                    throw ex;
                }
                return readDescription(rs);
            }
        }

        if (!noteColumnMissing) {
            try {
                String note = rs.getString("note");
                if (note != null) {
                    return note;
                }
            } catch (SQLException ex) {
                if (!handleMissingNote(ex)) {
                    throw ex;
                }
            }
        }

        return null;
    }

    private String resolvedExpenseNameColumn() {
        if (!expenseNameColumnMissing) {
            return "expense_name";
        }
        if (!descriptionColumnMissing) {
            return "description";
        }
        return null;
    }

    private String resolvedNoteColumn() {
        if (!noteColumnMissing) {
            return "note";
        }
        return null;
    }

    private String resolvedUserColumn() {
        if (!createdByColumnMissing) {
            return "created_by";
        }
        if (!userIdColumnMissing) {
            return "user_id";
        }
        return null;
    }

    private Long readUserId(ResultSet rs) throws SQLException {
        String column = resolvedUserColumn();
        if (column == null) {
            return null;
        }
        try {
            Object user = rs.getObject(column);
            if (user instanceof Number number) {
                return number.longValue();
            }
        } catch (SQLException ex) {
            if (handleMissingUserColumn(column, ex)) {
                return readUserId(rs);
            }
            throw ex;
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

    private boolean handleMissingDescriptionColumn(String column, SQLException ex) {
        if (!isMissingColumn(ex, column)) {
            return false;
        }
        String normalized = column == null ? "" : column.toLowerCase();
        synchronized (schemaLock) {
            if ("expense_name".equals(normalized) && !expenseNameColumnMissing) {
                expenseNameColumnMissing = true;
                System.err.println("Gider tablosunda 'expense_name' sütunu bulunamadı."
                        + " 'description' veya 'note' sütunları kullanılacak. Ayrıntı: " + ex.getMessage());
            } else if ("description".equals(normalized) && !descriptionColumnMissing) {
                descriptionColumnMissing = true;
                System.err.println("Gider tablosunda 'description' sütunu bulunamadı."
                        + " Kayıtlar 'expense_name' sütununda saklanacak. Ayrıntı: " + ex.getMessage());
            }
        }
        return true;
    }

    private boolean handleMissingNote(SQLException ex) {
        if (!isMissingColumn(ex, "note")) {
            return false;
        }
        if (!noteColumnMissing) {
            synchronized (schemaLock) {
                if (!noteColumnMissing) {
                    noteColumnMissing = true;
                    System.err.println("Gider tablosunda 'note' sütunu bulunamadı."
                            + " Not bilgisi kaydedilmeyecek. Ayrıntı: " + ex.getMessage());
                }
            }
        }
        return true;
    }

    private boolean handleMissingUserColumn(String column, SQLException ex) {
        if (!isMissingColumn(ex, column)) {
            return false;
        }
        String normalized = column == null ? "" : column.toLowerCase();
        synchronized (schemaLock) {
            if ("created_by".equals(normalized) && !createdByColumnMissing) {
                createdByColumnMissing = true;
                System.err.println("Gider tablosunda 'created_by' sütunu bulunamadı."
                        + " Kullanıcı bilgisi kaydedilmeyecek. Ayrıntı: " + ex.getMessage());
            } else if ("user_id".equals(normalized) && !userIdColumnMissing) {
                userIdColumnMissing = true;
                System.err.println("Gider tablosunda 'user_id' sütunu bulunamadı."
                        + " Kullanıcı bilgisi kaydedilmeyecek. Ayrıntı: " + ex.getMessage());
            }
        }
        return true;
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
        if (handleMissingDescriptionColumn("expense_name", ex)) {
            adjusted = true;
        }
        if (handleMissingDescriptionColumn("description", ex)) {
            adjusted = true;
        }
        if (handleMissingNote(ex)) {
            adjusted = true;
        }
        if (handleMissingUserColumn("created_by", ex)) {
            adjusted = true;
        }
        if (handleMissingUserColumn("user_id", ex)) {
            adjusted = true;
        }
        return adjusted;
    }

    private InsertPlan buildInsertPlan() {
        List<ParameterBinder> binders = new ArrayList<>();
        List<String> columns = new ArrayList<>();

        columns.add("amount");
        binders.add((ps, index, expense) -> ps.setBigDecimal(index, safeAmount(expense.getAmount())));

        String expenseNameColumn = resolvedExpenseNameColumn();
        if (expenseNameColumn != null) {
            columns.add(expenseNameColumn);
            binders.add((ps, index, expense) -> ps.setString(index, prepareExpenseName(expense.getDescription())));
        }

        String noteColumn = resolvedNoteColumn();
        if (noteColumn != null) {
            columns.add(noteColumn);
            binders.add((ps, index, expense) -> {
                String note = prepareExpenseNote(expense.getDescription());
                if (note == null) {
                    ps.setNull(index, Types.VARCHAR);
                } else {
                    ps.setString(index, note);
                }
            });
        }

        columns.add("expense_date");
        binders.add((ps, index, expense) -> ps.setDate(index, sqlDate(expense.getExpenseDate())));

        String userColumn = resolvedUserColumn();
        if (userColumn != null) {
            columns.add(userColumn);
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

        String expenseNameColumn = resolvedExpenseNameColumn();
        if (expenseNameColumn != null) {
            first = appendAssignment(sql, binders, first, expenseNameColumn,
                    (ps, index, expense) -> ps.setString(index, prepareExpenseName(expense.getDescription())));
        }

        String noteColumn = resolvedNoteColumn();
        if (noteColumn != null) {
            first = appendAssignment(sql, binders, first, noteColumn,
                    (ps, index, expense) -> {
                        String note = prepareExpenseNote(expense.getDescription());
                        if (note == null) {
                            ps.setNull(index, Types.VARCHAR);
                        } else {
                            ps.setString(index, note);
                        }
                    });
        }

        first = appendAssignment(sql, binders, first, "expense_date",
                (ps, index, expense) -> ps.setDate(index, sqlDate(expense.getExpenseDate())));

        String userColumn = resolvedUserColumn();
        if (userColumn != null) {
            first = appendAssignment(sql, binders, first, userColumn, (ps, index, expense) -> {
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
