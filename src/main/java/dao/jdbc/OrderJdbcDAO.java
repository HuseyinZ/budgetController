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
import java.util.List;
import java.util.Optional;

public class OrderJdbcDAO implements OrderDAO {

    private final DataSource dataSource;
    private final Connection externalConnection;

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
        OrderStatus status = OrderStatus.valueOf(rs.getString("status"));

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
        final String sql = "UPDATE orders SET status=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, status.name());
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
