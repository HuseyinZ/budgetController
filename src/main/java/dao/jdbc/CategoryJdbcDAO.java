package dao.jdbc;

import DataConnection.Db;
import dao.CategoryDAO;
import model.Category;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CategoryJdbcDAO implements CategoryDAO {

    private Category map(ResultSet rs) throws SQLException {
        Category c = new Category();
        c.setId(rs.getLong("id"));
        try { c.getClass().getMethod("setName", String.class).invoke(c, rs.getString("name")); } catch (Exception ignore) {}

        // NEW
        try {
            java.sql.Timestamp cr = rs.getTimestamp("created_at");
            java.sql.Timestamp up = rs.getTimestamp("updated_at");
            if (cr != null) c.setCreatedAt(cr.toLocalDateTime());
            if (up != null) c.setUpdatedAt(up.toLocalDateTime());
        } catch (SQLException ignore) {}

        return c;
    }


    @Override
    public Long create(Category e) {
        final String sql = "INSERT INTO categories (name) VALUES (?)";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            String name = null;
            try { name = (String) e.getClass().getMethod("getName").invoke(e); } catch (Exception ignore) {}
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            throw new SQLException("No generated key for categories");
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public void update(Category e) {
        final String sql = "UPDATE categories SET name=?, updated_at=NOW() WHERE id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            String name = null;
            try { name = (String) e.getClass().getMethod("getName").invoke(e); } catch (Exception ignore) {}
            ps.setString(1, name);
            ps.setLong(2, (Long) e.getClass().getMethod("getId").invoke(e));
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
        catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public void deleteById(Long id) {
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM categories WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public Optional<Category> findById(Long id) {
        final String sql = "SELECT * FROM categories WHERE id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public List<Category> findAll(int offset, int limit) {
        final String sql = "SELECT * FROM categories ORDER BY name LIMIT ? OFFSET ?";
        List<Category> list = new ArrayList<>();
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit); ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
        return list;
    }

    @Override
    public List<Category> searchByName(String q, int limit) {
        final String sql = "SELECT * FROM categories WHERE name LIKE ? ORDER BY name LIMIT ?";
        List<Category> list = new ArrayList<>();
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "%" + q + "%");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
        return list;
    }
}
