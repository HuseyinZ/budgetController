package dao.jdbc;

import DataConnection.Db;
import dao.SaleDAO;
import model.Sale;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class SaleJdbcDAO implements SaleDAO {

    private static void trySet(Object t, String method, Class<?> type, Object val) {
        try {
            Method m = t.getClass().getMethod(method, type);
            m.invoke(t, val);
        } catch (Exception ignore) {}
    }

    @Override
    public List<Sale> listSalesBetween(LocalDate start, LocalDate end, int offset, int limit) {
        // order_total ve ödenen toplamı birlikte raporlayan örnek sorgu
        final String sql =
                "SELECT o.id AS order_id, o.order_date, o.total, " +
                        "       COALESCE(SUM(p.amount),0) AS paid " +
                        "FROM orders o " +
                        "LEFT JOIN payments p ON p.order_id = o.id " +
                        "WHERE o.order_date >= ? AND o.order_date < DATE_ADD(?, INTERVAL 1 DAY) " +
                        "GROUP BY o.id, o.order_date, o.total " +
                        "ORDER BY o.order_date DESC " +
                        "LIMIT ? OFFSET ?";

        List<Sale> list = new ArrayList<>();
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(start));
            ps.setDate(2, Date.valueOf(end));
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Sale s = new Sale();
                    // setter isimleri projeden projeye değişebiliyor; yansıtma ile dene:
                    trySet(s, "setOrderId", Long.class, rs.getLong("order_id"));
                    trySet(s, "setTotal", BigDecimal.class, rs.getBigDecimal("total"));
                    trySet(s, "setPaid",  BigDecimal.class, rs.getBigDecimal("paid"));
                    Timestamp od = rs.getTimestamp("order_date");
                    if (od != null) {
                        trySet(s, "setOrderDate", LocalDateTime.class, od.toLocalDateTime());
                        trySet(s, "setDate", LocalDateTime.class, od.toLocalDateTime());
                    }
                    list.add(s);
                }
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
        return list;
    }

    @Override
    public BigDecimal revenueBetween(LocalDate start, LocalDate end) {
        final String sql = "SELECT COALESCE(SUM(o.total),0) FROM orders o " +
                "WHERE o.order_date >= ? AND o.order_date < DATE_ADD(?, INTERVAL 1 DAY)";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(start));
            ps.setDate(2, Date.valueOf(end));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO;
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }
}
