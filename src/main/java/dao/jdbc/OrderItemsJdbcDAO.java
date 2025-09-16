package dao.jdbc;

import DataConnection.Db;
import dao.OrderItemsDAO;
import model.OrderItem;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class OrderItemsJdbcDAO implements OrderItemsDAO {

    private OrderItem map(ResultSet rs) throws SQLException {
        OrderItem it = new OrderItem();
        it.setId(rs.getLong("id"));
        it.setOrderId(rs.getLong("order_id"));
        it.setProductId(rs.getLong("product_id"));
        it.setQuantity(rs.getInt("quantity"));
        it.setUnitPrice(rs.getBigDecimal("unit_price"));
        try { it.setProductName(rs.getString("product_name")); } catch (SQLException ignore) {}
        try { it.setNetAmount(rs.getBigDecimal("net_amount")); } catch (SQLException ignore) {}
        try { it.setTaxAmount(rs.getBigDecimal("tax_amount")); } catch (SQLException ignore) {}
        try { it.setLineTotal(rs.getBigDecimal("line_total")); } catch (SQLException ignore) {}

        // NEW: timestamps (kolonlar SELECT'e dahil değilse try-catch yakalar)
        try {
            java.sql.Timestamp c = rs.getTimestamp("created_at");
            java.sql.Timestamp u = rs.getTimestamp("updated_at");
            if (c != null) it.setCreatedAt(c.toLocalDateTime());
            if (u != null) it.setUpdatedAt(u.toLocalDateTime());
        } catch (SQLException ignore) {}

        return it;
    }

    @Override
    public Long create(OrderItem e) {
        final boolean hasName = e.getProductName() != null && !e.getProductName().isBlank();
        final String sql = hasName
                ? "INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_price) VALUES (?,?,?,?,?)"
                : "INSERT INTO order_items (order_id, product_id,           quantity, unit_price) VALUES (?,?,?,?)";

        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setLong(i++, e.getOrderId());
            ps.setLong(i++, e.getProductId());
            if (hasName) ps.setString(i++, e.getProductName());
            ps.setInt(i++, e.getQuantity());
            ps.setBigDecimal(i, e.getUnitPrice());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            throw new SQLException("No generated key for order_items");
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }


    @Override
    public void update(OrderItem e) {
        final String sql = "UPDATE order_items SET quantity=?, unit_price=?, updated_at=NOW() WHERE id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, e.getQuantity());
            ps.setBigDecimal(2, e.getUnitPrice());
            ps.setLong(3, e.getId());
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }
    @Override
    public void removeAllForOrder(Long orderId) {
        final String sql = "DELETE FROM order_items WHERE order_id=?";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }


    @Override
    public void deleteById(Long id) {
        final String sql = "DELETE FROM order_items WHERE id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }


    @Override
    public Optional<OrderItem> findById(Long id) {
        final String sql = "SELECT * FROM order_items WHERE id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public List<OrderItem> findAll(int offset, int limit) {
        final String sql = "SELECT * FROM order_items ORDER BY id LIMIT ? OFFSET ?";
        List<OrderItem> list = new ArrayList<>();
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
        return list;
    }

    @Override
    public List<OrderItem> findByOrderId(Long orderId) {
        final String sql = "SELECT * FROM order_items WHERE order_id=? ORDER BY id";
        List<OrderItem> list = new ArrayList<>();
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
        return list;
    }

    @Override
    public void addOrIncrement(Long orderId, Long productId, int quantity, BigDecimal unitPrice) {
        try (Connection c = Db.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE order_items SET quantity = quantity + ?, unit_price = ? WHERE order_id=? AND product_id=?")) {
                upd.setInt(1, quantity);
                upd.setBigDecimal(2, unitPrice);
                upd.setLong(3, orderId);
                upd.setLong(4, productId);
                int rows = upd.executeUpdate();

                if (rows == 0) {
                    // Ürün adı snapshot'ı varsa servis katmanından set edilmiştir
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_price) VALUES (?,?,?,?,?)")) {
                        ins.setLong(1, orderId);
                        ins.setLong(2, productId);
                        // product_name'i OrderItem üzerinden vermek istersen bu metodu overload edebiliriz.
                        ins.setString(3, null); // <- İstersen servis burada gerçek adı set etsin
                        ins.setInt(4, quantity);
                        ins.setBigDecimal(5, unitPrice);
                        ins.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }


    @Override
    public void decrementOrRemove(Long orderItemId, int quantity) {
        try (Connection c = Db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE order_items SET quantity = quantity - ? WHERE id=?")) {
                ps.setInt(1, quantity);
                ps.setLong(2, orderItemId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps2 = c.prepareStatement("DELETE FROM order_items WHERE id=? AND quantity<=0")) {
                ps2.setLong(1, orderItemId);
                ps2.executeUpdate();
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }
}
