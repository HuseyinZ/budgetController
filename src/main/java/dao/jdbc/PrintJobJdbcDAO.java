package dao.jdbc;

import DataConnection.Db;
import dao.PrintJobDAO;
import model.PrintJob;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PrintJobJdbcDAO implements PrintJobDAO {

    private final Connection externalConn;

    public PrintJobJdbcDAO()                  { this.externalConn = null; }
    public PrintJobJdbcDAO(Connection conn)   { this.externalConn = conn; }

    private PrintJob map(ResultSet rs) throws SQLException {
        PrintJob j = new PrintJob();
        j.setId(rs.getLong("id"));
        j.setOrderId(rs.getLong("order_id"));
        j.setPrinterId(rs.getInt("printer_id"));
        j.setPayload(rs.getString("payload"));
        j.setStatus(PrintJob.PrintJobStatus.valueOf(rs.getString("status")));
        j.setAttempts(rs.getInt("attempts"));
        j.setLastError(rs.getString("last_error"));
        Timestamp pt = rs.getTimestamp("printed_at");
        if (pt != null) j.setPrintedAt(pt.toLocalDateTime());
        Timestamp ct = rs.getTimestamp("created_at");
        if (ct != null) j.setCreatedAt(ct.toLocalDateTime());
        return j;
    }

    @Override
    public Long enqueue(PrintJob job) {
        final String sql = "INSERT INTO print_jobs (order_id, printer_id, payload, status, attempts) " +
                "VALUES (?, ?, ?, ?, ?)";
        Connection c = null;
        try {
            c = acquire();
            try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, job.getOrderId());
                ps.setInt(2, job.getPrinterId());
                ps.setString(3, job.getPayload());
                ps.setString(4, job.getStatus().name());
                ps.setInt(5, job.getAttempts());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
                throw new SQLException("print_jobs için id alınamadı");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            closeIfOwned(c);
        }
    }

    @Override
    public Optional<PrintJob> findById(Long id) {
        final String sql = "SELECT * FROM print_jobs WHERE id=?";
        Connection c = null;
        try {
            c = acquire();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            closeIfOwned(c);
        }
    }

    @Override
    public List<PrintJob> findPending(int limit) {
        final String sql = "SELECT * FROM print_jobs WHERE status IN ('PENDING','FAILED') " +
                "ORDER BY created_at ASC LIMIT ?";
        List<PrintJob> list = new ArrayList<>();
        Connection c = null;
        try {
            c = acquire();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            closeIfOwned(c);
        }
        return list;
    }

    @Override
    public void markPrinted(Long id) {
        update("UPDATE print_jobs SET status='PRINTED', printed_at=NOW(), last_error=NULL WHERE id=?",
                ps -> ps.setLong(1, id));
    }

    @Override
    public void markFailed(Long id, String error) {
        update("UPDATE print_jobs SET status='FAILED', attempts=attempts+1, last_error=? WHERE id=?",
                ps -> { ps.setString(1, truncate(error, 500)); ps.setLong(2, id); });
    }

    // ---- yardımcılar ----
    @FunctionalInterface private interface PsBinder { void bind(PreparedStatement ps) throws SQLException; }

    private Connection acquire() throws SQLException {
        return externalConn != null ? externalConn : Db.getConnection();
    }

    private void closeIfOwned(Connection c) {
        if (externalConn == null && c != null) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }

    private void update(String sql, PsBinder b) {
        Connection c = null;
        try {
            c = acquire();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                b.bind(ps);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            closeIfOwned(c);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
