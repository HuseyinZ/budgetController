package dao.jdbc;

import DataConnection.Db;
import dao.PaymentDAO;
import model.Payment;
import model.PaymentMethod;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PaymentJdbcDAO implements PaymentDAO {

    private Payment map(ResultSet rs) throws SQLException {
        Payment p = new Payment();
        p.setId(rs.getLong("id"));
        p.setOrderId(rs.getLong("order_id"));
        Object cashierObj = null;
        try { cashierObj = rs.getObject("cashier_id"); } catch (SQLException ignore) {}
        if (cashierObj != null) p.setCashierId(((Number) cashierObj).longValue());
        p.setAmount(rs.getBigDecimal("amount"));
        p.setMethod(model.PaymentMethod.valueOf(rs.getString("method")));
        java.sql.Timestamp paid = null;
        try { paid = rs.getTimestamp("paid_at"); } catch (SQLException ignore) {}
        if (paid != null) p.setPaidAt(paid.toLocalDateTime());

        // NEW
        try {
            java.sql.Timestamp c = rs.getTimestamp("created_at");
            java.sql.Timestamp u = rs.getTimestamp("updated_at");
            if (c != null) p.setCreatedAt(c.toLocalDateTime());
            if (u != null) p.setUpdatedAt(u.toLocalDateTime());
        } catch (SQLException ignore) {}

        return p;
    }


    @Override
    public Long create(Payment e) {
        // paid_at verilmişse yaz; verilmemişse sütunu hiç geçmeyerek DB default CURRENT_TIMESTAMP’i kullandır
        final boolean withPaidAt = e.getPaidAt() != null;
        final String sql = withPaidAt
                ? "INSERT INTO payments (order_id, cashier_id, amount, method, paid_at) VALUES (?,?,?,?,?)"
                : "INSERT INTO payments (order_id, cashier_id, amount, method) VALUES (?,?,?,?)";

        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            int i = 1;
            ps.setLong(i++, e.getOrderId());
            if (e.getCashierId() == null) ps.setNull(i++, Types.BIGINT); else ps.setLong(i++, e.getCashierId());
            ps.setBigDecimal(i++, e.getAmount());
            ps.setString(i++, e.getMethod().name());
            if (withPaidAt) ps.setTimestamp(i++, Timestamp.valueOf(e.getPaidAt()));

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            throw new SQLException("No generated key for payments");
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void update(Payment e) {
        // paid_at null ise mevcut değeri koru (COALESCE ile)
        final String sql =
                "UPDATE payments SET cashier_id=?, amount=?, method=?, " +
                        "paid_at = COALESCE(?, paid_at), updated_at=NOW() WHERE id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (e.getCashierId() == null) ps.setNull(1, Types.BIGINT); else ps.setLong(1, e.getCashierId());
            ps.setBigDecimal(2, e.getAmount());
            ps.setString(3, e.getMethod().name());
            if (e.getPaidAt() == null) ps.setNull(4, Types.TIMESTAMP);
            else ps.setTimestamp(4, Timestamp.valueOf(e.getPaidAt()));
            ps.setLong(5, e.getId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void deleteById(Long id) {
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM payments WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public Optional<Payment> findById(Long id) {
        final String sql = "SELECT id, order_id, cashier_id, amount, method, paid_at FROM payments WHERE id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public List<Payment> findAll(int offset, int limit) {
        final String sql = "SELECT id, order_id, cashier_id, amount, method, paid_at " +
                "FROM payments ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Payment> list = new ArrayList<>();
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
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
    @Override
    public List<Payment> findByDateRange(LocalDate start, LocalDate end) {
        final String sql = "SELECT id, order_id, cashier_id, amount, method, paid_at " +
                "FROM payments WHERE paid_at >= ? AND paid_at < ? ORDER BY paid_at";
        List<Payment> list = new ArrayList<>();
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(start.atStartOfDay()));
            ps.setTimestamp(2, Timestamp.valueOf(end.atStartOfDay()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
        return list;
    }


    @Override
    public List<Payment> findByOrderId(Long orderId) {
        final String sql = "SELECT id, order_id, cashier_id, amount, method, paid_at " +
                "FROM payments WHERE order_id=? ORDER BY id";
        List<Payment> list = new ArrayList<>();
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return list;
    }

    @Override
    public BigDecimal totalPaidForOrder(Long orderId) {
        final String sql = "SELECT COALESCE(SUM(amount),0) FROM payments WHERE order_id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }
}
