package dao.jdbc;

import DataConnection.Db;
import dao.ProductDAO;
import model.Product;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ProductJdbcDAO implements ProductDAO {

    // ---- küçük yansıtma yardımcıları: model setter isimleri farklıysa iş görür ----
    private static void trySet(Object target, String method, Class<?> type, Object value) {
        try {
            Method m = target.getClass().getMethod(method, type);
            m.invoke(target, value);
        } catch (Exception ignore) {}
    }
    private static String getName(ResultSet rs) throws SQLException { return rs.getString("name"); }
    private static BigDecimal getPrice(ResultSet rs) throws SQLException { return rs.getBigDecimal("unit_price"); }
    private static Integer getStock(ResultSet rs) throws SQLException { return rs.getInt("stock"); }

    private Product map(ResultSet rs) throws SQLException {
        Product p = new Product();
        p.setId(rs.getLong("id"));
        // isim/fiyat/stok map'ini sende nasıl yazdıysak aynı kalsın...
        try { p.getClass().getMethod("setPName", String.class).invoke(p, rs.getString("name")); } catch (Exception ignore) {}
        try { p.getClass().getMethod("setName",  String.class).invoke(p, rs.getString("name")); } catch (Exception ignore) {}
        try { p.getClass().getMethod("setUnitPrice", java.math.BigDecimal.class).invoke(p, rs.getBigDecimal("unit_price")); } catch (Exception ignore) {}
        try { p.getClass().getMethod("setPrice",     java.math.BigDecimal.class).invoke(p, rs.getBigDecimal("unit_price")); } catch (Exception ignore) {}
        try { p.getClass().getMethod("setStock", Integer.class).invoke(p, rs.getInt("stock")); } catch (Exception ignore) {}
        try { p.getClass().getMethod("setQuantity", Integer.class).invoke(p, rs.getInt("stock")); } catch (Exception ignore) {}
        try { p.getClass().getMethod("setCategoryId", Long.class).invoke(p, rs.getLong("category_id")); } catch (Exception ignore) {}

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
    public Long create(Product e) {
        final String sql = "INSERT INTO products (name, unit_price, stock, category_id) VALUES (?,?,?,?)";
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            // isim
            try {
                Method g = e.getClass().getMethod("getPName");
                ps.setString(1, (String) g.invoke(e));
            } catch (Exception ex1) {
                try {
                    Method g2 = e.getClass().getMethod("getName");
                    ps.setString(1, (String) g2.invoke(e));
                } catch (Exception ex2) {
                    ps.setString(1, null);
                }
            }
            // fiyat
            try {
                Method g = e.getClass().getMethod("getUnitPrice");
                ps.setBigDecimal(2, (BigDecimal) g.invoke(e));
            } catch (Exception ex1) {
                try {
                    Method g2 = e.getClass().getMethod("getPrice");
                    ps.setBigDecimal(2, (BigDecimal) g2.invoke(e));
                } catch (Exception ex2) {
                    ps.setBigDecimal(2, null);
                }
            }
            // stok
            try {
                Method g = e.getClass().getMethod("getStock");
                ps.setInt(3, (Integer) g.invoke(e));
            } catch (Exception ex1) {
                try {
                    Method g2 = e.getClass().getMethod("getQuantity");
                    ps.setInt(3, (Integer) g2.invoke(e));
                } catch (Exception ex2) {
                    ps.setNull(3, Types.INTEGER);
                }
            }
            // category_id opsiyonel
            try {
                Method g = e.getClass().getMethod("getCategoryId");
                Object v = g.invoke(e);
                if (v == null) ps.setNull(4, Types.BIGINT);
                else ps.setLong(4, (Long) v);
            } catch (Exception ignore) { ps.setNull(4, Types.BIGINT); }

            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
            throw new SQLException("No generated key for products");
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public void update(Product e) {
        final String sql = "UPDATE products SET name=?, unit_price=?, stock=?, category_id=?, updated_at=NOW() WHERE id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            // name
            String name = null;
            try { name = (String) e.getClass().getMethod("getPName").invoke(e); } catch (Exception ignore) {}
            if (name == null) try { name = (String) e.getClass().getMethod("getName").invoke(e); } catch (Exception ignore) {}
            ps.setString(1, name);
            // price
            BigDecimal price = null;
            try { price = (BigDecimal) e.getClass().getMethod("getUnitPrice").invoke(e); } catch (Exception ignore) {}
            if (price == null) try { price = (BigDecimal) e.getClass().getMethod("getPrice").invoke(e); } catch (Exception ignore) {}
            ps.setBigDecimal(2, price);
            // stock
            Integer stock = null;
            try { stock = (Integer) e.getClass().getMethod("getStock").invoke(e); } catch (Exception ignore) {}
            if (stock == null) try { stock = (Integer) e.getClass().getMethod("getQuantity").invoke(e); } catch (Exception ignore) {}
            if (stock == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, stock);
            // category
            Long catId = null;
            try { catId = (Long) e.getClass().getMethod("getCategoryId").invoke(e); } catch (Exception ignore) {}
            if (catId == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, catId);
            // id
            Long id = (Long) e.getClass().getMethod("getId").invoke(e);
            ps.setLong(5, id);

            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
        catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public void deleteById(Long id) {
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM products WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public Optional<Product> findById(Long id) {
        final String sql = "SELECT * FROM products WHERE id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public List<Product> findAll(int offset, int limit) {
        final String sql = "SELECT * FROM products ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Product> list = new ArrayList<>();
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit); ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
        return list;
    }

    @Override
    public List<Product> searchByName(String q, int limit) {
        final String sql = "SELECT * FROM products WHERE name LIKE ? ORDER BY name LIMIT ?";
        List<Product> list = new ArrayList<>();
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "%" + q + "%");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
        return list;
    }

    @Override
    public List<Product> findByCategory(Long categoryId, int offset, int limit) {
        final String sql = "SELECT * FROM products WHERE category_id=? ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Product> list = new ArrayList<>();
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, categoryId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) { throw new RuntimeException(ex); }
        return list;
    }

    @Override
    public void updateStock(Long productId, int delta) {
        final String sql = "UPDATE products SET stock = stock + ?, updated_at=NOW() WHERE id=?";
        try (Connection c = Db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setLong(2, productId);
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }
}
