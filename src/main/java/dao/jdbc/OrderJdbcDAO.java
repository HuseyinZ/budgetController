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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class OrderJdbcDAO implements OrderDAO {

    private final DataSource dataSource;
    private final Connection externalConnection;
    private final Object statusLock = new Object();
    private final Set<OrderStatus> unsupportedStatuses = EnumSet.noneOf(OrderStatus.class);
    private final Set<String> unknownStatusValues = new HashSet<>();

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
        OrderStatus status = parseStatus(rs.getString("status"));

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

    private OrderStatus parseStatus(String value) {
        if (value == null) {
            return OrderStatus.PENDING;
        }
        try {
            return OrderStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            synchronized (statusLock) {
                if (unknownStatusValues.add(value)) {
                    System.err.println("Bilinmeyen sipariş durumu değeri ('" + value
                            + "'). 'PENDING' varsayıldı.");
                }
            }
            return OrderStatus.PENDING;
        }
    }

    @Override
    public Long create(Order e) {
        final String sql = "INSERT INTO orders (table_id, waiter_id, note, status) VALUES (?,?,?,?)";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                if (e.getTableId() == null) ps.setNull(1, Types.BIGINT); else ps.setLong(1, e.getTableId());
                if (e.getWaiterId() == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, e.getWaiterId());
                ps.setString(3, e.getNote());
                ps.setString(4, e.getStatus().name());

                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
                throw new SQLException("No generated key for orders");
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public void update(Order e) {
        final String sql =
                "UPDATE orders SET table_id=?, waiter_id=?, note=?, status=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                if (e.getTableId() == null) ps.setNull(1, Types.BIGINT); else ps.setLong(1, e.getTableId());
                if (e.getWaiterId() == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, e.getWaiterId());
                ps.setString(3, e.getNote());
                ps.setString(4, e.getStatus().name());
                ps.setLong(5, e.getId());
                ps.executeUpdate();
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
        final String sql =
                "SELECT * FROM orders WHERE status IN ('PENDING','IN_PROGRESS','READY') ORDER BY id DESC";
        List<Order> list = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
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
        final String sql =
                "SELECT * FROM orders WHERE table_id=? AND status IN ('PENDING','IN_PROGRESS','READY') ORDER BY id DESC LIMIT 1";
        Connection connection = null;
        try {
            connection = acquireConnection();
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
        updateStatusInternal(orderId, status, true);
    }

    private void updateStatusInternal(Long orderId, OrderStatus status, boolean allowFallback) {
        OrderStatus normalized = normalize(status);
        if (unsupportedStatuses.contains(normalized)) {
            OrderStatus fallback = fallback(normalized);
            if (fallback != null) {
                updateStatusInternal(orderId, fallback, false);
            }
            return;
        }

        final String sql = "UPDATE orders SET status=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, normalized.name());
                ps.setLong(2, orderId);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            if (allowFallback && handleUnsupportedStatus(normalized, ex)) {
                OrderStatus fallback = fallback(normalized);
                if (fallback != null) {
                    updateStatusInternal(orderId, fallback, false);
                    return;
                }
            }
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    private OrderStatus normalize(OrderStatus status) {
        return status == null ? OrderStatus.PENDING : status;
    }

    private OrderStatus fallback(OrderStatus status) {
        return switch (status) {
            case READY -> OrderStatus.IN_PROGRESS;
            case CANCELLED -> OrderStatus.PENDING;
            default -> null;
        };
    }

    private boolean handleUnsupportedStatus(OrderStatus status, SQLException ex) {
        if (!isUnsupportedStatus(ex)) {
            return false;
        }
        OrderStatus fallback = fallback(status);
        if (fallback == null) {
            return false;
        }
        synchronized (statusLock) {
            if (unsupportedStatuses.add(status)) {
                System.err.println("Sipariş durumu '" + status.name()
                        + "' veritabanında desteklenmiyor. '" + fallback.name()
                        + "' kullanılacak. Ayrıntı: " + ex.getMessage());
            }
        }
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
        final String sql =
                "UPDATE orders SET status='COMPLETED', closed_at=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setTimestamp(1, Timestamp.valueOf(
                        closedAt != null ? closedAt : LocalDateTime.now()));
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
}
