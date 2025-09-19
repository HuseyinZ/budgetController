package dao.jdbc;

import DataConnection.Db;
import dao.UserDAO;
import model.Role;
import model.User;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class UserJdbcDAO implements UserDAO {

    private static final String BASE_SELECT =
            "SELECT u.id, u.username, u.password_hash, u.full_name, u.is_active, u.created_at, " +
                    "       r.name AS role_name " +
                    "FROM users u LEFT JOIN roles r ON r.id = u.role_id";

    private User map(ResultSet rs) throws SQLException {
        String username = rs.getString("username");
        String passwordHash = rs.getString("password_hash");
        String roleName = rs.getString("role_name");
        Role role;
        try {
            role = Role.valueOf(roleName);
        } catch (Exception e) {
            role = Role.KASIYER;
        }

        // Artık fullName'i de ctor/setter ile veriyoruz
        String fullName = rs.getString("full_name");
        User u = (fullName != null && !fullName.isBlank())
                ? new User(username, passwordHash, role, fullName)
                : new User(username, passwordHash, role);

        u.setId(rs.getLong("id"));
        u.setActive(rs.getBoolean("is_active"));
        try {
            Timestamp cAt = rs.getTimestamp("created_at");
            if (cAt != null) u.setCreatedAt(cAt.toLocalDateTime());
            java.sql.Timestamp uAt = rs.getTimestamp("updated_at");
            if (uAt != null) u.setUpdatedAt(uAt.toLocalDateTime());
        } catch (SQLException ignore) {
        }
        return u;
    }

    @Override
    public Long create(User e) {
        final String sql =
                "INSERT INTO users (username, password_hash, full_name, role_id, is_active) " +
                        "VALUES (?, ?, ?, (SELECT id FROM roles WHERE name=?), ?)";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, e.getUsername());
            ps.setString(2, e.getPasswordHash());
            // Modelde fullName varsa onu yaz; yoksa güvenli fallback: username
            String fullName = e.getFullName() != null ? e.getFullName() : e.getUsername();
            ps.setString(3, fullName);
            ps.setString(4, e.getRole().name());
            ps.setBoolean(5, e.isActive());

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            throw new SQLException("No generated key for users");
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void update(User e) {
        final String sql =
                "UPDATE users SET username=?, password_hash=?, full_name=?, " +
                        "role_id=(SELECT id FROM roles WHERE name=?), is_active=?, updated_at=NOW() WHERE id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, e.getUsername());
            ps.setString(2, e.getPasswordHash());
            String fullName = e.getFullName() != null ? e.getFullName() : e.getUsername();
            ps.setString(3, fullName);
            ps.setString(4, e.getRole().name());
            ps.setBoolean(5, e.isActive());
            ps.setLong(6, e.getId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void deleteById(Long id) { /* değişmedi */
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM users WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public Optional<User> findById(Long id) {
        final String sql = BASE_SELECT + " WHERE u.id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    public List<User> findAll(int offset, int limit) {
        final String sql = BASE_SELECT + " ORDER BY u.username LIMIT ? OFFSET ?";
        List<User> out = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setInt(1, limit);
            ps.setInt(2, offset);

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


    @Override
    public Optional<User> findByUsername(String username) {
        final String sql = BASE_SELECT + " WHERE u.username=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void updateRole(Long userId, Role role) {
        final String sql = "UPDATE users SET role_id=(SELECT id FROM roles WHERE name=?), updated_at=NOW() WHERE id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, role.name());
            ps.setLong(2, userId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public long countByRole(Role role) {
        final String sql = "SELECT COUNT(*) FROM users WHERE role_id = (SELECT id FROM roles WHERE name=?)";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, role.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
            return 0L;
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }
}
