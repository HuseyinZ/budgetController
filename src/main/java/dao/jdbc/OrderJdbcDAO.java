package dao.jdbc;

import DataConnection.Db;
import dao.OrderDAO;
import model.Order;
import model.OrderStatus;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class OrderJdbcDAO implements OrderDAO {

    /* --------------------- Row mapper --------------------- */
    private Order map(ResultSet rs) throws SQLException {
        Long tableId  = (rs.getObject("table_id")  == null) ? null : rs.getLong("table_id");
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

        // opsiyonel zaman damgaları
        try {
            Timestamp cr = rs.getTimestamp("created_at");
            Timestamp up = rs.getTimestamp("updated_at");
            if (cr != null) o.setCreatedAt(cr.toLocalDateTime());
            if (up != null) o.setUpdatedAt(up.toLocalDateTime());
        } catch (SQLException ignore) { /* kolonlar yoksa sorun değil */ }

        return o;
    }

    /* --------------------- CrudRepository --------------------- */

    @Override
    public Long create(Order e) {
        final String sql =
                "INSERT INTO orders (table_id, waiter_id, note, status) VALUES (?,?,?,?)";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            if (e.getTableId() == null)  ps.setNull(1, Types.BIGINT); else ps.setLong(1, e.getTableId());
            if (e.getWaiterId() == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, e.getWaiterId());
            ps.setString(3, e.getNote());
            ps.setString(4, e.getStatus().name());

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            throw new SQLException("No generated key for orders");
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void update(Order e) {
        final String sql =
                "UPDATE orders SET table_id=?, waiter_id=?, note=?, status=?, updated_at=NOW() WHERE id=?";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            if (e.getTableId() == null)  ps.setNull(1, Types.BIGINT); else ps.setLong(1, e.getTableId());
            if (e.getWaiterId() == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, e.getWaiterId());
            ps.setString(3, e.getNote());
            ps.setString(4, e.getStatus().name());
            ps.setLong(5, e.getId());

            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void deleteById(Long id) {
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM orders WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public Optional<Order> findById(Long id) {
        final String sql = "SELECT * FROM orders WHERE id=?";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public List<Order> findAll(int offset, int limit) {
        final String sql = "SELECT * FROM orders ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Order> list = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return list;
    }

    /* --------------------- OrderDAO extra --------------------- */

    @Override
    public List<Order> findOpenOrders() {
        // COMPLETED dışındaki durumları istiyorsan: WHERE status <> 'COMPLETED'
        final String sql =
                "SELECT * FROM orders " +
                        "WHERE status IN ('PENDING','IN_PROGRESS','READY') " +
                        "ORDER BY id DESC";
        List<Order> list = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(map(rs));
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return list;
    }

    @Override
    public Optional<Order> findOpenOrderByTable(Long tableId) {
        final String sql =
                "SELECT * FROM orders " +
                        "WHERE table_id=? AND status IN ('PENDING','IN_PROGRESS','READY') " +
                        "ORDER BY id DESC LIMIT 1";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, tableId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void updateStatus(Long orderId, OrderStatus status) {
        final String sql = "UPDATE orders SET status=?, updated_at=NOW() WHERE id=?";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setLong(2, orderId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void assignTable(Long orderId, Long tableId) {
        final String sql = "UPDATE orders SET table_id=?, updated_at=NOW() WHERE id=?";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (tableId == null) ps.setNull(1, Types.BIGINT);
            else ps.setLong(1, tableId);
            ps.setLong(2, orderId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    /** Tek UPDATE ile kapatır: closed_at set eder ve status’u COMPLETED yapar. */
    @Override
    public void closeOrder(Long orderId, LocalDateTime closedAt) {
        final String sql =
                "UPDATE orders " +
                        "SET status='COMPLETED', closed_at=?, updated_at=NOW() " +
                        "WHERE id=?";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(
                    closedAt != null ? closedAt : LocalDateTime.now()));
            ps.setLong(2, orderId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void updateTotals(Long orderId,
                             BigDecimal subtotal,
                             BigDecimal taxTotal,
                             BigDecimal discountTotal,
                             BigDecimal total) {
        final String sql =
                "UPDATE orders " +
                        "SET subtotal=?, tax_total=?, discount_total=?, total=?, updated_at=NOW() " +
                        "WHERE id=?";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBigDecimal(1, subtotal);
            ps.setBigDecimal(2, taxTotal);
            ps.setBigDecimal(3, discountTotal);
            ps.setBigDecimal(4, total);
            ps.setLong(5, orderId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }
}
