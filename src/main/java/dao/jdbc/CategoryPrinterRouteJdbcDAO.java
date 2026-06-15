package dao.jdbc;

import DataConnection.Db;
import dao.CategoryPrinterRouteDAO;
import model.CategoryPrinterRoute;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class CategoryPrinterRouteJdbcDAO implements CategoryPrinterRouteDAO {

    private final Connection externalConn;

    public CategoryPrinterRouteJdbcDAO()                  { this.externalConn = null; }
    public CategoryPrinterRouteJdbcDAO(Connection conn)   { this.externalConn = conn; }

    @Override
    public List<Integer> findPrinterIdsByCategory(Long categoryId) {
        List<Integer> ids = new ArrayList<>();
        final String sql = "SELECT printer_id FROM category_printer_routes WHERE category_id=?";
        Connection c = null;
        try {
            c = acquire();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, categoryId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) ids.add(rs.getInt(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            closeIfOwned(c);
        }
        return ids;
    }

    @Override
    public List<CategoryPrinterRoute> findAll() {
        List<CategoryPrinterRoute> list = new ArrayList<>();
        final String sql = "SELECT id, category_id, printer_id, created_at FROM category_printer_routes";
        Connection c = null;
        try {
            c = acquire();
            try (PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CategoryPrinterRoute r = new CategoryPrinterRoute();
                    r.setId(rs.getLong("id"));
                    r.setCategoryId(rs.getLong("category_id"));
                    r.setPrinterId(rs.getInt("printer_id"));
                    Timestamp ct = rs.getTimestamp("created_at");
                    if (ct != null) r.setCreatedAt(ct.toLocalDateTime());
                    list.add(r);
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
    public void link(Long categoryId, Integer printerId) {
        // MySQL INSERT IGNORE — duplikatlar UNIQUE constraint ile sessizce geçilir.
        final String sql = "INSERT IGNORE INTO category_printer_routes (category_id, printer_id) VALUES (?, ?)";
        Connection c = null;
        try {
            c = acquire();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, categoryId);
                ps.setInt(2, printerId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            closeIfOwned(c);
        }
    }

    @Override
    public void unlink(Long categoryId, Integer printerId) {
        execute("DELETE FROM category_printer_routes WHERE category_id=? AND printer_id=?",
                ps -> { ps.setLong(1, categoryId); ps.setInt(2, printerId); });
    }

    @Override
    public void deleteByCategory(Long categoryId) {
        execute("DELETE FROM category_printer_routes WHERE category_id=?",
                ps -> ps.setLong(1, categoryId));
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

    private void execute(String sql, PsBinder binder) {
        Connection c = null;
        try {
            c = acquire();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                binder.bind(ps);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            closeIfOwned(c);
        }
    }
}
