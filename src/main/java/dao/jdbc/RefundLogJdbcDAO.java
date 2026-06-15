package dao.jdbc;

import DataConnection.Db;
import dao.RefundLogDAO;
import model.RefundLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * RefundLog için JDBC implementasyonu.
 *
 * <p>Tablo: {@code refund_log}. Append-only — UPDATE/DELETE açılmaz.
 *
 * <p>Şema (MySQL):
 * <pre>{@code
 * CREATE TABLE IF NOT EXISTS refund_log (
 *   id          BIGINT AUTO_INCREMENT PRIMARY KEY,
 *   user_id     BIGINT,
 *   user_name   VARCHAR(255),
 *   action_type VARCHAR(32) NOT NULL,
 *   table_no    INT,
 *   order_id    BIGINT,
 *   product_name VARCHAR(255),
 *   quantity    INT,
 *   amount      DECIMAL(19,2),
 *   reason      VARCHAR(500),
 *   created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *   INDEX idx_refund_created (created_at),
 *   INDEX idx_refund_user    (user_id)
 * );
 * }</pre>
 *
 * <p>Tablo yoksa ilk kullanımda otomatik oluşturulur ({@link #ensureTable()}).
 */
public class RefundLogJdbcDAO implements RefundLogDAO {

    private static final Logger LOG = LoggerFactory.getLogger(RefundLogJdbcDAO.class);

    private static final String COLS =
            "id, user_id, user_name, action_type, table_no, order_id, " +
            "product_name, quantity, amount, reason, created_at";

    private volatile boolean tableEnsured = false;

    public RefundLogJdbcDAO() {}

    @Override
    public Long create(RefundLog log) {
        if (log == null) throw new IllegalArgumentException("log null");
        ensureTable();
        final String sql = "INSERT INTO refund_log " +
                "(user_id, user_name, action_type, table_no, order_id, " +
                " product_name, quantity, amount, reason) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            if (log.getUserId() == null) ps.setNull(1, Types.BIGINT);
            else                          ps.setLong(1, log.getUserId());
            ps.setString(2, log.getUserName());
            ps.setString(3, log.getActionType() == null ? "UNKNOWN" : log.getActionType().name());
            if (log.getTableNo() == null) ps.setNull(4, Types.INTEGER);
            else                           ps.setInt(4, log.getTableNo());
            if (log.getOrderId() == null) ps.setNull(5, Types.BIGINT);
            else                           ps.setLong(5, log.getOrderId());
            ps.setString(6, log.getProductName());
            if (log.getQuantity() == null) ps.setNull(7, Types.INTEGER);
            else                            ps.setInt(7, log.getQuantity());
            ps.setBigDecimal(8, log.getAmount() == null ? BigDecimal.ZERO : log.getAmount());
            ps.setString(9, log.getReason());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            throw new SQLException("refund_log için generated key alınamadı");
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public List<RefundLog> findAll() {
        ensureTable();
        return query("SELECT " + COLS + " FROM refund_log ORDER BY created_at DESC, id DESC LIMIT 1000",
                ps -> {});
    }

    @Override
    public List<RefundLog> findByDateRange(LocalDate fromInclusive, LocalDate toInclusive) {
        ensureTable();
        return query("SELECT " + COLS + " FROM refund_log " +
                     "WHERE DATE(created_at) BETWEEN ? AND ? " +
                     "ORDER BY created_at DESC, id DESC",
                ps -> {
                    ps.setDate(1, Date.valueOf(fromInclusive));
                    ps.setDate(2, Date.valueOf(toInclusive));
                });
    }

    @Override
    public List<RefundLog> findByUserId(Long userId) {
        ensureTable();
        return query("SELECT " + COLS + " FROM refund_log WHERE user_id=? " +
                     "ORDER BY created_at DESC, id DESC LIMIT 500",
                ps -> ps.setLong(1, userId));
    }

    // ---- yardımcılar ----

    @FunctionalInterface
    private interface PsBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<RefundLog> query(String sql, PsBinder b) {
        List<RefundLog> out = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            b.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return out;
    }

    private RefundLog map(ResultSet rs) throws SQLException {
        RefundLog r = new RefundLog();
        r.setId(rs.getLong("id"));
        long uid = rs.getLong("user_id");
        if (!rs.wasNull()) r.setUserId(uid);
        r.setUserName(rs.getString("user_name"));
        String at = rs.getString("action_type");
        if (at != null) {
            try { r.setActionType(RefundLog.ActionType.valueOf(at)); }
            catch (IllegalArgumentException ignore) {}
        }
        int tno = rs.getInt("table_no");
        if (!rs.wasNull()) r.setTableNo(tno);
        long oid = rs.getLong("order_id");
        if (!rs.wasNull()) r.setOrderId(oid);
        r.setProductName(rs.getString("product_name"));
        int q = rs.getInt("quantity");
        if (!rs.wasNull()) r.setQuantity(q);
        r.setAmount(rs.getBigDecimal("amount"));
        r.setReason(rs.getString("reason"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) r.setCreatedAt(ts.toLocalDateTime());
        return r;
    }

    /**
     * refund_log tablosunu yoksa oluşturur. Hem MySQL hem H2 (test) için çalışır.
     * Sadece ilk çağrıda kontrol edilir, sonraki çağrılar fast-path geçer.
     */
    private synchronized void ensureTable() {
        if (tableEnsured) return;
        final String mysql = "CREATE TABLE IF NOT EXISTS refund_log (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "user_id BIGINT," +
                "user_name VARCHAR(255)," +
                "action_type VARCHAR(32) NOT NULL," +
                "table_no INT," +
                "order_id BIGINT," +
                "product_name VARCHAR(255)," +
                "quantity INT," +
                "amount DECIMAL(19,2)," +
                "reason VARCHAR(500)," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")";
        try (Connection c = Db.getConnection();
             Statement st = c.createStatement()) {
            st.execute(mysql);
            tableEnsured = true;
        } catch (SQLException ex) {
            LOG.warn("refund_log tablosu hazırlanamadı: {}", ex.getMessage());
        }
    }
}
