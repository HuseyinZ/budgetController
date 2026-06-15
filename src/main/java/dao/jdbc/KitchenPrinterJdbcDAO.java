package dao.jdbc;

import DataConnection.Db;
import dao.KitchenPrinterDAO;
import model.KitchenPrinter;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class KitchenPrinterJdbcDAO implements KitchenPrinterDAO {

    private static final String COLS =
            "id, code, display_name, host, port, char_per_line, code_page, is_active, note, " +
            "created_at, updated_at";

    private final Connection externalConn;

    public KitchenPrinterJdbcDAO()                    { this.externalConn = null; }
    public KitchenPrinterJdbcDAO(Connection conn)     { this.externalConn = conn; }

    // ---- map ----
    private KitchenPrinter map(ResultSet rs) throws SQLException {
        KitchenPrinter p = new KitchenPrinter();
        p.setId(rs.getLong("id"));
        p.setCode(rs.getString("code"));
        p.setDisplayName(rs.getString("display_name"));
        p.setHost(rs.getString("host"));
        p.setPort(rs.getInt("port"));
        p.setCharPerLine(rs.getInt("char_per_line"));
        p.setCodePage(rs.getInt("code_page"));
        p.setActive(rs.getBoolean("is_active"));
        p.setNote(rs.getString("note"));
        Timestamp cr = rs.getTimestamp("created_at");
        Timestamp up = rs.getTimestamp("updated_at");
        if (cr != null) p.setCreatedAt(cr.toLocalDateTime());
        if (up != null) p.setUpdatedAt(up.toLocalDateTime());
        return p;
    }

    // ---- CRUD ----
    @Override
    public Integer create(KitchenPrinter e) {
        final String sql = "INSERT INTO kitchen_printers " +
                "(code, display_name, host, port, char_per_line, code_page, is_active, note) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try {
            return runReturning(sql, ps -> {
                ps.setString(1, e.getCode());
                ps.setString(2, e.getDisplayName());
                ps.setString(3, e.getHost());
                ps.setInt(4, e.getPort());
                ps.setInt(5, e.getCharPerLine());
                ps.setInt(6, e.getCodePage());
                ps.setBoolean(7, e.isActive());
                ps.setString(8, e.getNote());
            });
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public void update(KitchenPrinter e) {
        final String sql = "UPDATE kitchen_printers SET " +
                "code=?, display_name=?, host=?, port=?, char_per_line=?, code_page=?, " +
                "is_active=?, note=?, updated_at=NOW() WHERE id=?";
        run(sql, ps -> {
            ps.setString(1, e.getCode());
            ps.setString(2, e.getDisplayName());
            ps.setString(3, e.getHost());
            ps.setInt(4, e.getPort());
            ps.setInt(5, e.getCharPerLine());
            ps.setInt(6, e.getCodePage());
            ps.setBoolean(7, e.isActive());
            ps.setString(8, e.getNote());
            ps.setLong(9, e.getId());
        });
    }

    @Override
    public void deleteById(Integer id) {
        run("DELETE FROM kitchen_printers WHERE id=?", ps -> ps.setInt(1, id));
    }

    @Override
    public Optional<KitchenPrinter> findById(Integer id) {
        return queryOne("SELECT " + COLS + " FROM kitchen_printers WHERE id=?",
                ps -> ps.setInt(1, id));
    }

    @Override
    public List<KitchenPrinter> findAll(int offset, int limit) {
        return queryMany("SELECT " + COLS + " FROM kitchen_printers ORDER BY code LIMIT ? OFFSET ?",
                ps -> { ps.setInt(1, limit); ps.setInt(2, offset); });
    }

    @Override
    public Optional<KitchenPrinter> findByCode(String code) {
        return queryOne("SELECT " + COLS + " FROM kitchen_printers WHERE code=? LIMIT 1",
                ps -> ps.setString(1, code));
    }

    @Override
    public List<KitchenPrinter> findActive() {
        return queryMany("SELECT " + COLS + " FROM kitchen_printers WHERE is_active=1 ORDER BY code",
                ps -> {});
    }

    // ---- yardımcılar (try-with-resources doğru kullanım) ----
    @FunctionalInterface private interface PsBinder { void bind(PreparedStatement ps) throws SQLException; }

    private Connection acquireConn() throws SQLException {
        return externalConn != null ? externalConn : Db.getConnection();
    }

    private boolean isManaged()                                { return externalConn != null; }

    private void closeIfOwned(Connection c) throws SQLException {
        if (!isManaged() && c != null && !c.isClosed()) c.close();
    }

    private int run(String sql, PsBinder b) {
        Connection c = null;
        try {
            c = acquireConn();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                b.bind(ps);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            try { closeIfOwned(c); } catch (SQLException ignored) {}
        }
    }

    private int runReturning(String sql, PsBinder b) throws SQLException {
        Connection c = null;
        try {
            c = acquireConn();
            try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                b.bind(ps);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getInt(1);
                }
                throw new SQLException("Auto-generated id alınamadı");
            }
        } finally {
            closeIfOwned(c);
        }
    }

    private Optional<KitchenPrinter> queryOne(String sql, PsBinder b) {
        Connection c = null;
        try {
            c = acquireConn();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                b.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            try { closeIfOwned(c); } catch (SQLException ignored) {}
        }
    }

    private List<KitchenPrinter> queryMany(String sql, PsBinder b) {
        List<KitchenPrinter> list = new ArrayList<>();
        Connection c = null;
        try {
            c = acquireConn();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                b.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(map(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            try { closeIfOwned(c); } catch (SQLException ignored) {}
        }
        return list;
    }
}
