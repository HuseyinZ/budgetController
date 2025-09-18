package dao.jdbc;

import DataConnection.Db;
import dao.OrderDAO;
import model.Order;
import model.OrderStatus;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class OrderJdbcDAO implements OrderDAO {

    private static final OrderStatus[] STATUS_VALUES = OrderStatus.values();

    private final DataSource dataSource;
    private final Connection externalConnection;
    private final Object statusLock = new Object();
    private final Object statusModeLock = new Object();
    private final Set<OrderStatus> unsupportedStatuses = EnumSet.noneOf(OrderStatus.class);
    private volatile Set<OrderStatus> supportedStatuses = EnumSet.allOf(OrderStatus.class);
    private volatile boolean statusValueSetDetermined;
    private final Set<String> unknownStatusValues = new HashSet<>();
    private volatile boolean statusOrdinalMode;
    private volatile boolean statusModeDetermined;

    public OrderJdbcDAO() {
        this(Db.getDataSource(), null);
    }

    public OrderJdbcDAO(DataSource dataSource) {
        this(dataSource, null);
    }

    public OrderJdbcDAO(Connection connection) {
        this(Db.getDataSource(), connection);
    }

    private OrderJdbcDAO(DataSource dataSource, Connection externalConnection) {
        this.dataSource = dataSource;
        this.externalConnection = externalConnection;
    }

    private Connection acquireConnection() throws SQLException {
        if (externalConnection != null) {
            return externalConnection;
        }
        if (dataSource == null) {
            throw new IllegalStateException("No DataSource configured for OrderJdbcDAO");
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

    private Order map(ResultSet rs) throws SQLException {
        Long tableId = (rs.getObject("table_id") == null) ? null : rs.getLong("table_id");
        Long waiterId = (rs.getObject("waiter_id") == null) ? null : rs.getLong("waiter_id");
        OrderStatus status = parseStatus(rs.getObject("status"));

        Order o = new Order(tableId, waiterId, status);
        o.setId(rs.getLong("id"));

        Timestamp od = rs.getTimestamp("order_date");
        if (od != null) o.setOrderDate(od.toLocalDateTime());

        o.setNote(rs.getString("note"));
        o.setSubtotal(rs.getBigDecimal("subtotal"));
        o.setTaxTotal(rs.getBigDecimal("tax_total"));
        o.setDiscountTotal(rs.getBigDecimal("discount_total"));
        o.setTotal(rs.getBigDecimal("total"));

        Timestamp ca = rs.getTimestamp("closed_at");
        if (ca != null) o.setClosedAt(ca.toLocalDateTime());

        try {
            Timestamp cr = rs.getTimestamp("created_at");
            Timestamp up = rs.getTimestamp("updated_at");
            if (cr != null) o.setCreatedAt(cr.toLocalDateTime());
            if (up != null) o.setUpdatedAt(up.toLocalDateTime());
        } catch (SQLException ignore) {
            // optional columns
        }

        return o;
    }

    private OrderStatus parseStatus(Object value) {
        if (value == null) {
            return OrderStatus.PENDING;
        }
        if (value instanceof Number number) {
            switchToOrdinalMode("Tablodan sayısal 'status' değeri okundu: " + number);
            return fromOrdinal(number.intValue(), number.toString());
        }
        String text = value.toString();
        if (text == null) {
            return OrderStatus.PENDING;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return OrderStatus.PENDING;
        }
        if (isNumericString(trimmed)) {
            try {
                int ordinal = Integer.parseInt(trimmed);
                switchToOrdinalMode("Tablodan sayısal 'status' değeri okundu: " + trimmed);
                return fromOrdinal(ordinal, trimmed);
            } catch (NumberFormatException ignore) {
                // fall through
            }
        }
        return parseStatusText(trimmed);
    }

    private OrderStatus parseStatusText(String value) {
        try {
            return OrderStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            recordUnknownStatus(value);
            return OrderStatus.PENDING;
        }
    }

    private OrderStatus fromOrdinal(int ordinal, String rawValue) {
        if (ordinal >= 0 && ordinal < STATUS_VALUES.length) {
            return STATUS_VALUES[ordinal];
        }
        recordUnknownStatus(rawValue);
        return OrderStatus.PENDING;
    }

    private void recordUnknownStatus(String value) {
        String key = value == null ? "null" : value;
        synchronized (statusLock) {
            if (unknownStatusValues.add(key)) {
                System.err.println("Bilinmeyen sipariş durumu değeri ('" + key
                        + "'). 'PENDING' varsayıldı.");
            }
        }
    }

    private boolean isNumericString(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Long create(Order e) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Connection connection = null;
            try {
                connection = acquireConnection();
                detectStatusMode(connection);
                final String sql = "INSERT INTO orders (table_id, waiter_id, note, status) VALUES (?,?,?,?)";
                try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    if (e.getTableId() == null) ps.setNull(1, Types.BIGINT); else ps.setLong(1, e.getTableId());
                    if (e.getWaiterId() == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, e.getWaiterId());
                    ps.setString(3, e.getNote());
                    setStatusParameter(ps, 4, normalize(e.getStatus()));

                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            return rs.getLong(1);
                        }
                    }
                    throw new SQLException("No generated key for orders");
                }
            } catch (SQLException ex) {
                if (!statusOrdinalMode && handleStatusWriteIncompatibility(ex, connection)) {
                    continue;
                }
                throw new RuntimeException(ex);
            } finally {
                close(connection);
            }
        }
        throw new IllegalStateException("orders.status column uyumsuz");
    }

    @Override
    public void update(Order e) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Connection connection = null;
            try {
                connection = acquireConnection();
                detectStatusMode(connection);
                final String sql =
                        "UPDATE orders SET table_id=?, waiter_id=?, note=?, status=?, updated_at=NOW() WHERE id=?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    if (e.getTableId() == null) ps.setNull(1, Types.BIGINT); else ps.setLong(1, e.getTableId());
                    if (e.getWaiterId() == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, e.getWaiterId());
                    ps.setString(3, e.getNote());
                    setStatusParameter(ps, 4, normalize(e.getStatus()));
                    ps.setLong(5, e.getId());
                    ps.executeUpdate();
                    return;
                }
            } catch (SQLException ex) {
                if (!statusOrdinalMode && handleStatusWriteIncompatibility(ex, connection)) {
                    continue;
                }
                throw new RuntimeException(ex);
            } finally {
                close(connection);
            }
        }
        throw new IllegalStateException("orders.status column uyumsuz");
    }

    @Override
    public void deleteById(Long id) {
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM orders WHERE id=?")) {
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
    public Optional<Order> findById(Long id) {
        final String sql = "SELECT * FROM orders WHERE id=?";
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
    public List<Order> findAll(int offset, int limit) {
        final String sql = "SELECT * FROM orders ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Order> list = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(map(rs));
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
        return list;
    }

    @Override
    public List<Order> findOpenOrders() {
        List<Order> list = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            detectStatusMode(connection);
            final String sql = "SELECT * FROM orders WHERE " + openStatusesCondition()
                    + " ORDER BY id DESC";
            try (PreparedStatement ps = connection.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
        return list;
    }

    @Override
    public Optional<Order> findOpenOrderByTable(Long tableId) {
        Connection connection = null;
        try {
            connection = acquireConnection();
            detectStatusMode(connection);
            final String sql = "SELECT * FROM orders WHERE table_id=? AND " + openStatusesCondition()
                    + " ORDER BY id DESC LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, tableId);
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
    public void updateStatus(Long orderId, OrderStatus status) {
        updateStatusInternal(orderId, status, EnumSet.noneOf(OrderStatus.class));
    }

    private void updateStatusInternal(Long orderId, OrderStatus status, EnumSet<OrderStatus> visited) {
        OrderStatus normalized = normalize(status);
        if (!visited.add(normalized)) {
            return;
        }

        if (isStatusMarkedUnsupported(normalized)) {
            OrderStatus fallbackStatus = fallback(normalized, visited);
            if (fallbackStatus != null && fallbackStatus != normalized) {
                updateStatusInternal(orderId, fallbackStatus, visited);
            }
            return;
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            Connection connection = null;
            try {
                connection = acquireConnection();
                detectStatusMode(connection);
                if (isStatusMarkedUnsupported(normalized)) {
                    OrderStatus fallbackStatus = fallback(normalized, visited);
                    if (fallbackStatus != null && fallbackStatus != normalized) {
                        close(connection);
                        connection = null;
                        updateStatusInternal(orderId, fallbackStatus, visited);
                    }
                    return;
                }
                final String sql = "UPDATE orders SET status=?, updated_at=NOW() WHERE id=?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    setStatusParameter(ps, 1, normalized);
                    ps.setLong(2, orderId);
                    ps.executeUpdate();
                    return;
                }
            } catch (SQLException ex) {
                if (!statusOrdinalMode && handleStatusWriteIncompatibility(ex, connection)) {
                    continue;
                }
                if (handleUnsupportedStatus(normalized, ex)) {
                    OrderStatus fallbackStatus = fallback(normalized, visited);
                    if (fallbackStatus != null && fallbackStatus != normalized) {
                        updateStatusInternal(orderId, fallbackStatus, visited);
                    }
                    return;
                }
                throw new RuntimeException(ex);
            } finally {
                close(connection);
            }
        }
        throw new RuntimeException("orders.status column uyumsuz");
    }

    private OrderStatus normalize(OrderStatus status) {
        return status == null ? OrderStatus.PENDING : status;
    }

    private OrderStatus fallback(OrderStatus status) {
        return fallback(status, Collections.emptySet());
    }

    private OrderStatus fallback(OrderStatus status, Set<OrderStatus> excluded) {
        OrderStatus normalized = normalize(status);
        Set<OrderStatus> supportedSnapshot = supportedStatuses;
        Set<OrderStatus> unsupportedSnapshot;
        synchronized (statusLock) {
            if (unsupportedStatuses.isEmpty()) {
                unsupportedSnapshot = EnumSet.noneOf(OrderStatus.class);
            } else {
                unsupportedSnapshot = EnumSet.copyOf(unsupportedStatuses);
            }
        }
        return fallbackWithSnapshots(normalized, supportedSnapshot, unsupportedSnapshot, excluded);
    }

    private OrderStatus fallbackWithSnapshots(OrderStatus status,
                                              Set<OrderStatus> supportedSnapshot,
                                              Set<OrderStatus> unsupportedSnapshot,
                                              Set<OrderStatus> excluded) {
        OrderStatus normalized = normalize(status);
        if (isCandidateAllowed(normalized, supportedSnapshot, unsupportedSnapshot, excluded)) {
            return normalized;
        }
        for (int i = normalized.ordinal() - 1; i >= 0; i--) {
            OrderStatus candidate = STATUS_VALUES[i];
            if (isCandidateAllowed(candidate, supportedSnapshot, unsupportedSnapshot, excluded)) {
                return candidate;
            }
        }
        for (int i = normalized.ordinal() + 1; i < STATUS_VALUES.length; i++) {
            OrderStatus candidate = STATUS_VALUES[i];
            if (isCandidateAllowed(candidate, supportedSnapshot, unsupportedSnapshot, excluded)) {
                return candidate;
            }
        }
        for (OrderStatus candidate : supportedSnapshot) {
            if (isCandidateAllowed(candidate, supportedSnapshot, unsupportedSnapshot, excluded)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isCandidateAllowed(OrderStatus candidate,
                                       Set<OrderStatus> supportedSnapshot,
                                       Set<OrderStatus> unsupportedSnapshot,
                                       Set<OrderStatus> excluded) {
        if (!supportedSnapshot.contains(candidate)) {
            return false;
        }
        if (unsupportedSnapshot.contains(candidate)) {
            return false;
        }
        return excluded == null || !excluded.contains(candidate);
    }

    private boolean isStatusMarkedUnsupported(OrderStatus status) {
        synchronized (statusLock) {
            return unsupportedStatuses.contains(status);
        }
    }

    private void warnUnsupportedStatus(OrderStatus status, String detail) {
        boolean added;
        synchronized (statusLock) {
            added = unsupportedStatuses.add(status);
        }
        if (!added) {
            return;
        }
        OrderStatus fallback = fallback(status);
        StringBuilder message = new StringBuilder("Sipariş durumu '")
                .append(status.name())
                .append("' veritabanı şemasında desteklenmiyor.");
        if (fallback != null && fallback != status) {
            message.append(" '").append(fallback.name()).append("' kullanılacak.");
        } else {
            message.append(" Yazma denemeleri atlanacak.");
        }
        if (detail != null && !detail.isBlank()) {
            message.append(" Ayrıntı: ").append(detail);
        }
        System.err.println(message);
    }

    private boolean handleUnsupportedStatus(OrderStatus status, SQLException ex) {
        if (!isUnsupportedStatus(ex)) {
            return false;
        }
        warnUnsupportedStatus(status, ex.getMessage());
        return true;
    }

    private boolean isUnsupportedStatus(SQLException ex) {
        SQLException current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("data truncated") && lower.contains("status")) {
                    return true;
                }
                if (lower.contains("incorrect") && lower.contains("enum") && lower.contains("status")) {
                    return true;
                }
                if (lower.contains("unknown column") && lower.contains("status")) {
                    return true;
                }
            }
            String state = current.getSQLState();
            if (state != null && (state.equals("01000") || state.equals("22001") || state.equals("HY000"))) {
                String msg = current.getMessage();
                if (msg != null && msg.toLowerCase().contains("status")) {
                    return true;
                }
            }
            current = current.getNextException();
        }
        return false;
    }

    @Override
    public void assignTable(Long orderId, Long tableId) {
        final String sql = "UPDATE orders SET table_id=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                if (tableId == null) ps.setNull(1, Types.BIGINT);
                else ps.setLong(1, tableId);
                ps.setLong(2, orderId);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public void closeOrder(Long orderId, LocalDateTime closedAt) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Connection connection = null;
            try {
                connection = acquireConnection();
                detectStatusMode(connection);
                final String sql =
                        "UPDATE orders SET status=?, closed_at=?, updated_at=NOW() WHERE id=?";
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    setStatusParameter(ps, 1, OrderStatus.COMPLETED);
                    ps.setTimestamp(2, Timestamp.valueOf(
                            closedAt != null ? closedAt : LocalDateTime.now()));
                    ps.setLong(3, orderId);
                    ps.executeUpdate();
                    return;
                }
            } catch (SQLException ex) {
                if (!statusOrdinalMode && handleStatusWriteIncompatibility(ex, connection)) {
                    continue;
                }
                throw new RuntimeException(ex);
            } finally {
                close(connection);
            }
        }
        throw new RuntimeException("orders.status column uyumsuz");
    }

    @Override
    public void updateTotals(Long orderId,
                             BigDecimal subtotal,
                             BigDecimal taxTotal,
                             BigDecimal discountTotal,
                             BigDecimal total) {
        final String sql =
                "UPDATE orders SET subtotal=?, tax_total=?, discount_total=?, total=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setBigDecimal(1, subtotal);
                ps.setBigDecimal(2, taxTotal);
                ps.setBigDecimal(3, discountTotal);
                ps.setBigDecimal(4, total);
                ps.setLong(5, orderId);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    private void detectStatusMode(Connection connection) {
        if (statusOrdinalMode || statusModeDetermined) {
            return;
        }
        Boolean numeric = lookupStatusColumnNumeric(connection);
        if (numeric == null) {
            return;
        }
        if (numeric) {
            switchToOrdinalMode("Veritabanı şeması sayısal 'status' sütunu bildiriyor.");
        } else {
            markStatusModeKnown();
        }
    }

    private void detectSupportedStatusesFromTypeName(String typeName) {
        if (statusValueSetDetermined || typeName == null) {
            return;
        }
        String trimmed = typeName.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String lower = trimmed.toLowerCase();
        if (!lower.contains("enum")) {
            return;
        }
        int start = trimmed.indexOf('(');
        int end = trimmed.lastIndexOf(')');
        if (start < 0 || end <= start) {
            return;
        }
        String inner = trimmed.substring(start + 1, end);
        if (inner.isBlank()) {
            return;
        }
        Set<OrderStatus> detected = EnumSet.noneOf(OrderStatus.class);
        String[] tokens = inner.split(",");
        for (String token : tokens) {
            String cleaned = unquote(token);
            if (cleaned == null || cleaned.isBlank()) {
                continue;
            }
            String candidate = cleaned.trim().toUpperCase();
            try {
                detected.add(OrderStatus.valueOf(candidate));
            } catch (IllegalArgumentException ignore) {
                // skip unknown literals
            }
        }
        applyDetectedSupportedStatuses(detected, trimmed);
    }

    private void applyDetectedSupportedStatuses(Set<OrderStatus> detected, String detail) {
        if (detected == null || detected.isEmpty()) {
            return;
        }
        synchronized (statusLock) {
            if (statusValueSetDetermined) {
                return;
            }
            supportedStatuses = EnumSet.copyOf(detected);
            statusValueSetDetermined = true;
        }
        for (OrderStatus status : STATUS_VALUES) {
            if (!supportedStatuses.contains(status)) {
                warnUnsupportedStatus(status, detail);
            }
        }
    }

    private String unquote(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '\'' && last == '\'')
                    || (first == '"' && last == '"')
                    || (first == '`' && last == '`')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private Boolean lookupStatusColumnNumeric(Connection connection) {
        if (connection == null) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT status FROM orders LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            int type = meta.getColumnType(1);
            if (isNumericType(type)) {
                return true;
            }
            if (isStringType(type)) {
                detectSupportedStatusesFromTypeName(meta.getColumnTypeName(1));
                return false;
            }
            String typeName = meta.getColumnTypeName(1);
            detectSupportedStatusesFromTypeName(typeName);
            if (typeName != null) {
                String lower = typeName.toLowerCase();
                if (lower.contains("enum") || lower.contains("char") || lower.contains("text")) {
                    return false;
                }
                if (lower.contains("int") || lower.contains("number")) {
                    return true;
                }
            }
        } catch (SQLException ignore) {
            // fall back to DatabaseMetaData below
        }
        try {
            DatabaseMetaData meta = connection.getMetaData();
            String catalog = safeCatalog(connection);
            String schema = safeSchema(connection);
            Boolean direct = findStatusColumnNumeric(meta, catalog, schema, "orders");
            if (direct != null) {
                return direct;
            }
            return findStatusColumnNumeric(meta, catalog, schema, "ORDERS");
        } catch (SQLException ignore) {
            return null;
        }
    }

    private Boolean findStatusColumnNumeric(DatabaseMetaData meta,
                                            String catalog,
                                            String schema,
                                            String tablePattern) throws SQLException {
        if (meta == null) {
            return null;
        }
        try (ResultSet rs = meta.getColumns(catalog, schema, tablePattern, "status")) {
            while (rs.next()) {
                int dataType = rs.getInt("DATA_TYPE");
                if (isNumericType(dataType)) {
                    return true;
                }
                String typeName = rs.getString("TYPE_NAME");
                detectSupportedStatusesFromTypeName(typeName);
                if (isStringType(dataType)) {
                    return false;
                }
                if (typeName != null) {
                    String lower = typeName.toLowerCase();
                    if (lower.contains("enum") || lower.contains("char") || lower.contains("text")) {
                        return false;
                    }
                    if (lower.contains("int") || lower.contains("number")) {
                        return true;
                    }
                }
            }
        }
        return null;
    }

    private boolean isNumericType(int type) {
        return type == Types.TINYINT
                || type == Types.SMALLINT
                || type == Types.INTEGER
                || type == Types.BIGINT
                || type == Types.NUMERIC
                || type == Types.DECIMAL;
    }

    private boolean isStringType(int type) {
        return type == Types.CHAR
                || type == Types.VARCHAR
                || type == Types.LONGVARCHAR
                || type == Types.NCHAR
                || type == Types.NVARCHAR
                || type == Types.LONGNVARCHAR
                || type == Types.CLOB
                || type == Types.NCLOB;
    }

    private String safeCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException ex) {
            return null;
        }
    }

    private String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError ex) {
            return null;
        }
    }

    private void markStatusModeKnown() {
        synchronized (statusModeLock) {
            statusModeDetermined = true;
        }
    }

    private void switchToOrdinalMode(String detail) {
        synchronized (statusModeLock) {
            if (!statusOrdinalMode) {
                statusOrdinalMode = true;
                statusModeDetermined = true;
                StringBuilder message = new StringBuilder(
                        "Sipariş tablosundaki 'status' sütunu sayısal görünüyor. Enum adları yerine sıra numaraları kullanılacak.");
                if (detail != null && !detail.isBlank()) {
                    message.append(" Ayrıntı: ").append(detail);
                }
                System.err.println(message);
            } else if (!statusModeDetermined) {
                statusModeDetermined = true;
            }
        }
    }

    private boolean handleStatusWriteIncompatibility(SQLException ex, Connection connection) {
        if (!isStatusConversionError(ex)) {
            return false;
        }
        Boolean numeric = lookupStatusColumnNumeric(connection);
        if (numeric != null) {
            if (!numeric) {
                return false;
            }
        } else if (!messageSuggestsNumericStatus(ex.getMessage())) {
            return false;
        }
        switchToOrdinalMode(ex.getMessage());
        return true;
    }

    private boolean isStatusConversionError(SQLException ex) {
        SQLException current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("incorrect") && lower.contains("integer") && lower.contains("status")) {
                    return true;
                }
                if (lower.contains("data truncated") && lower.contains("status")) {
                    return true;
                }
            }
            String state = current.getSQLState();
            if (state != null && (state.equals("22001") || state.equals("22003") || state.equals("HY000"))) {
                String msg = current.getMessage();
                if (msg != null && msg.toLowerCase().contains("status")) {
                    return true;
                }
            }
            current = current.getNextException();
        }
        return false;
    }

    private boolean messageSuggestsNumericStatus(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        if (!lower.contains("status")) {
            return false;
        }
        if (lower.contains("integer")) {
            return true;
        }
        return lower.contains("data truncated") && !lower.contains("enum");
    }

    private String openStatusesCondition() {
        if (statusOrdinalMode) {
            return "status IN (" + OrderStatus.PENDING.ordinal() + ","
                    + OrderStatus.IN_PROGRESS.ordinal() + ","
                    + OrderStatus.READY.ordinal() + ")";
        }
        if (!statusModeDetermined) {
            return "(status IN ('PENDING','IN_PROGRESS','READY') OR status IN ("
                    + OrderStatus.PENDING.ordinal() + ","
                    + OrderStatus.IN_PROGRESS.ordinal() + ","
                    + OrderStatus.READY.ordinal() + "))";
        }
        return "status IN ('PENDING','IN_PROGRESS','READY')";
    }

    private void setStatusParameter(PreparedStatement ps, int parameterIndex, OrderStatus status) throws SQLException {
        OrderStatus normalized = normalize(status);
        OrderStatus effective = fallback(normalized);
        if (effective == null) {
            effective = normalized;
        }
        if (statusOrdinalMode) {
            ps.setInt(parameterIndex, effective.ordinal());
        } else {
            ps.setString(parameterIndex, effective.name());
        }
    }
}
