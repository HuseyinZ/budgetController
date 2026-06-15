package dao.jdbc;

import DataConnection.Db;
import dao.ReservationDAO;
import model.Reservation;
import model.ReservationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC implementasyonu — {@link dao.ReservationDAO}.
 *
 * <p>Şema {@code reservations(id, table_no, start_time, end_time,
 * customer_name, customer_phone, party_size, notes, status, created_at, created_by)}.
 */
public class ReservationJdbcDAO implements ReservationDAO {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationJdbcDAO.class);

    @Override
    public Long create(Reservation r) {
        final String sql =
                "INSERT INTO reservations " +
                "(table_no, start_time, end_time, customer_name, customer_phone, party_size, notes, status, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, r.getTableNo());
            ps.setTimestamp(2, Timestamp.valueOf(r.getStartTime()));
            ps.setTimestamp(3, Timestamp.valueOf(r.getEndTime()));
            ps.setString(4, safe(r.getCustomerName(), 120, "?"));
            if (r.getCustomerPhone() == null) ps.setNull(5, Types.VARCHAR);
            else ps.setString(5, safe(r.getCustomerPhone(), 40, ""));
            ps.setInt(6, Math.max(1, r.getPartySize()));
            if (r.getNotes() == null) ps.setNull(7, Types.VARCHAR);
            else ps.setString(7, safe(r.getNotes(), 500, ""));
            ps.setString(8, (r.getStatus() == null ? ReservationStatus.BOOKED : r.getStatus()).name());
            if (r.getCreatedBy() == null) ps.setNull(9, Types.VARCHAR);
            else ps.setString(9, safe(r.getCreatedBy(), 80, ""));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            throw new SQLException("Generated key alınamadı");
        } catch (SQLException ex) {
            throw new RuntimeException("Rezervasyon eklenemedi: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void update(Reservation r) {
        final String sql =
                "UPDATE reservations SET table_no=?, start_time=?, end_time=?, " +
                "customer_name=?, customer_phone=?, party_size=?, notes=?, status=? WHERE id=?";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, r.getTableNo());
            ps.setTimestamp(2, Timestamp.valueOf(r.getStartTime()));
            ps.setTimestamp(3, Timestamp.valueOf(r.getEndTime()));
            ps.setString(4, safe(r.getCustomerName(), 120, "?"));
            if (r.getCustomerPhone() == null) ps.setNull(5, Types.VARCHAR);
            else ps.setString(5, safe(r.getCustomerPhone(), 40, ""));
            ps.setInt(6, Math.max(1, r.getPartySize()));
            if (r.getNotes() == null) ps.setNull(7, Types.VARCHAR);
            else ps.setString(7, safe(r.getNotes(), 500, ""));
            ps.setString(8, (r.getStatus() == null ? ReservationStatus.BOOKED : r.getStatus()).name());
            ps.setLong(9, r.getId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException("Rezervasyon güncellenemedi: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void deleteById(Long id) {
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM reservations WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException("Rezervasyon silinemedi: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Optional<Reservation> findById(Long id) {
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM reservations WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public List<Reservation> findAll(int offset, int limit) {
        final String sql = "SELECT * FROM reservations ORDER BY start_time DESC LIMIT ? OFFSET ?";
        List<Reservation> out = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException ex) {
            LOG.warn("findAll reservations hata: {}", ex.getMessage());
        }
        return out;
    }

    @Override
    public List<Reservation> findByDate(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        return findBetween(start, end);
    }

    @Override
    public List<Reservation> findBetween(LocalDateTime startInclusive, LocalDateTime endExclusive) {
        // Aralıkla ÇAKIŞAN tüm rezervasyonları döndür (sadece o aralıkta başlayanlar değil)
        final String sql =
                "SELECT * FROM reservations " +
                "WHERE start_time < ? AND end_time > ? " +
                "ORDER BY start_time, table_no";
        List<Reservation> out = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(endExclusive));
            ps.setTimestamp(2, Timestamp.valueOf(startInclusive));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException ex) {
            LOG.warn("findBetween reservations hata: {}", ex.getMessage());
        }
        return out;
    }

    @Override
    public List<Reservation> findOverlapping(int tableNo, LocalDateTime start, LocalDateTime end, Long ignoreId) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM reservations " +
                "WHERE table_no=? AND status <> 'CANCELLED' AND status <> 'NO_SHOW' " +
                "  AND start_time < ? AND end_time > ? ");
        if (ignoreId != null) sql.append(" AND id <> ? ");
        sql.append(" ORDER BY start_time");

        List<Reservation> out = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setInt(1, tableNo);
            ps.setTimestamp(2, Timestamp.valueOf(end));
            ps.setTimestamp(3, Timestamp.valueOf(start));
            if (ignoreId != null) ps.setLong(4, ignoreId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException ex) {
            LOG.warn("findOverlapping hata: {}", ex.getMessage());
        }
        return out;
    }

    @Override
    public Optional<Reservation> findNextForTable(int tableNo) {
        final String sql =
                "SELECT * FROM reservations " +
                "WHERE table_no=? AND status='BOOKED' AND end_time > NOW() " +
                "ORDER BY start_time ASC LIMIT 1";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, tableNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            LOG.debug("findNextForTable hata ({}): {}", tableNo, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<Reservation> findUpcoming(LocalDateTime from, int limitPerTable) {
        // Basit: tüm BOOKED + end > from, kullanıcı tarafında masa başına filtrelenir.
        final String sql =
                "SELECT * FROM reservations " +
                "WHERE status='BOOKED' AND end_time > ? " +
                "ORDER BY start_time ASC";
        List<Reservation> out = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(from));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException ex) {
            LOG.warn("findUpcoming hata: {}", ex.getMessage());
        }
        return out;
    }

    @Override
    public void updateStatus(long id, ReservationStatus status) {
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE reservations SET status=? WHERE id=?")) {
            ps.setString(1, (status == null ? ReservationStatus.BOOKED : status).name());
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException("Status güncellenemedi: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------

    private Reservation map(ResultSet rs) throws SQLException {
        Reservation r = new Reservation();
        r.setId(rs.getLong("id"));
        r.setTableNo(rs.getInt("table_no"));
        Timestamp st = rs.getTimestamp("start_time");
        Timestamp en = rs.getTimestamp("end_time");
        r.setStartTime(st == null ? null : st.toLocalDateTime());
        r.setEndTime(en == null ? null : en.toLocalDateTime());
        r.setCustomerName(rs.getString("customer_name"));
        r.setCustomerPhone(rs.getString("customer_phone"));
        try { r.setPartySize(rs.getInt("party_size")); } catch (SQLException ignore) {}
        r.setNotes(rs.getString("notes"));
        r.setStatus(ReservationStatus.parse(rs.getString("status")));
        Timestamp cr = rs.getTimestamp("created_at");
        if (cr != null) r.setCreatedAt(cr.toLocalDateTime());
        try { r.setCreatedBy(rs.getString("created_by")); } catch (SQLException ignore) {}
        return r;
    }

    private static String safe(String s, int max, String fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        if (t.isEmpty()) return fallback;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
