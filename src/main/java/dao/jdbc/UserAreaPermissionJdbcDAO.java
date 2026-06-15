package dao.jdbc;

import DataConnection.Db;
import dao.UserAreaPermissionDAO;
import model.UserAreaPermission;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class UserAreaPermissionJdbcDAO implements UserAreaPermissionDAO {

    private final Connection externalConn;

    public UserAreaPermissionJdbcDAO()                  { this.externalConn = null; }
    public UserAreaPermissionJdbcDAO(Connection conn)   { this.externalConn = conn; }

    private Connection acquire() throws SQLException {
        return externalConn != null ? externalConn : Db.getConnection();
    }

    private void closeIfOwned(Connection c) {
        if (externalConn == null && c != null) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }

    private UserAreaPermission map(ResultSet rs) throws SQLException {
        UserAreaPermission p = new UserAreaPermission();
        p.setId(rs.getLong("id"));
        p.setUserId(rs.getLong("user_id"));
        p.setBuilding(rs.getString("building"));
        p.setSection(rs.getString("section"));
        try {
            Timestamp ct = rs.getTimestamp("created_at");
            if (ct != null) p.setCreatedAt(ct.toLocalDateTime());
        } catch (SQLException ignore) {}
        return p;
    }

    @Override
    public List<UserAreaPermission> findByUserId(Long userId) {
        List<UserAreaPermission> list = new ArrayList<>();
        final String sql = "SELECT id, user_id, building, section, created_at " +
                "FROM user_area_permissions WHERE user_id=? ORDER BY building, section";
        Connection c = null;
        try {
            c = acquire();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, userId);
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
    public void grant(Long userId, String building, String section) {
        // INSERT IGNORE — UNIQUE (user_id, building, section) sayesinde duplikatlar sessiz geçer.
        final String sql = "INSERT IGNORE INTO user_area_permissions (user_id, building, section) VALUES (?, ?, ?)";
        Connection c = null;
        try {
            c = acquire();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.setString(2, building == null ? "" : building);
                ps.setString(3, section == null ? "" : section);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            closeIfOwned(c);
        }
    }

    @Override
    public void revoke(Long userId, String building, String section) {
        final String sql = "DELETE FROM user_area_permissions WHERE user_id=? AND building=? AND section=?";
        Connection c = null;
        try {
            c = acquire();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.setString(2, building == null ? "" : building);
                ps.setString(3, section == null ? "" : section);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            closeIfOwned(c);
        }
    }

    @Override
    public void deleteAllForUser(Long userId) {
        final String sql = "DELETE FROM user_area_permissions WHERE user_id=?";
        Connection c = null;
        try {
            c = acquire();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, userId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            closeIfOwned(c);
        }
    }
}
