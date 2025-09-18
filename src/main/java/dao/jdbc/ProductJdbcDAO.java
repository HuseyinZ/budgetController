package dao.jdbc;

import DataConnection.Db;
import dao.ProductDAO;
import model.Product;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ProductJdbcDAO implements ProductDAO {

    private final DataSource dataSource;
    private final Connection externalConnection;

    public ProductJdbcDAO() {
        this(Db.getDataSource(), null);
    }

    public ProductJdbcDAO(DataSource dataSource) {
        this(dataSource, null);
    }

    public ProductJdbcDAO(Connection connection) {
        this(Db.getDataSource(), connection);
    }

    private ProductJdbcDAO(DataSource dataSource, Connection externalConnection) {
        this.dataSource = dataSource;
        this.externalConnection = externalConnection;
    }

    private Connection acquireConnection() throws SQLException {
        if (externalConnection != null) {
            return externalConnection;
        }
        if (dataSource == null) {
            throw new IllegalStateException("No DataSource configured for ProductJdbcDAO");
        }
        return dataSource.getConnection();
    }

    private void close(Connection connection) {
        if (externalConnection == null && connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Product map(ResultSet rs) throws SQLException {
        Product p = new Product();
        p.setId(rs.getLong("id"));
        p.setName(rs.getString("name"));
        p.setUnitPrice(rs.getBigDecimal("unit_price"));

        int stockValue = rs.getInt("stock");
        if (!rs.wasNull()) {
            p.setStock(stockValue);
        } else {
            p.setStock(null);
        }

        long categoryValue = rs.getLong("category_id");
        if (!rs.wasNull()) {
            p.setCategoryId(categoryValue);
        } else {
            p.setCategoryId(null);
        }

        try {
            Timestamp created = rs.getTimestamp("created_at");
            Timestamp updated = rs.getTimestamp("updated_at");
            if (created != null) {
                p.setCreatedAt(created.toLocalDateTime());
            }
            if (updated != null) {
                p.setUpdatedAt(updated.toLocalDateTime());
            }
        } catch (SQLException ignore) {
            // optional columns
        }

        return p;
    }

    @Override
    public Long create(Product e) {
        final String sql = "INSERT INTO products (name, unit_price, stock, category_id) VALUES (?,?,?,?)";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, e.getName());
                ps.setBigDecimal(2, e.getUnitPrice());
                if (e.getStock() == null) {
                    ps.setNull(3, Types.INTEGER);
                } else {
                    ps.setInt(3, e.getStock());
                }
                if (e.getCategoryId() == null) {
                    ps.setNull(4, Types.BIGINT);
                } else {
                    ps.setLong(4, e.getCategoryId());
                }

                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
                throw new SQLException("No generated key for products");
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public void update(Product e) {
        final String sql = "UPDATE products SET name=?, unit_price=?, stock=?, category_id=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, e.getName());
                ps.setBigDecimal(2, e.getUnitPrice());
                if (e.getStock() == null) {
                    ps.setNull(3, Types.INTEGER);
                } else {
                    ps.setInt(3, e.getStock());
                }
                if (e.getCategoryId() == null) {
                    ps.setNull(4, Types.BIGINT);
                } else {
                    ps.setLong(4, e.getCategoryId());
                }
                ps.setLong(5, e.getId());
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public void deleteById(Long id) {
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM products WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public Optional<Product> findById(Long id) {
        final String sql = "SELECT * FROM products WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public List<Product> findAll(int offset, int limit) {
        final String sql = "SELECT * FROM products ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Product> list = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(map(rs));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
        return list;
    }

    @Override
    public List<Product> searchByName(String q, int limit) {
        final String sql = "SELECT * FROM products WHERE name LIKE ? ORDER BY name LIMIT ?";
        List<Product> list = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, "%" + q + "%");
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(map(rs));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
        return list;
    }

    @Override
    public List<Product> findByCategory(Long categoryId, int offset, int limit) {
        final String sql = "SELECT * FROM products WHERE category_id=? ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Product> list = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, categoryId);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(map(rs));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
        return list;
    }

    @Override
    public void updateStock(Long productId, int delta) {
        final String sql = "UPDATE products SET stock = COALESCE(stock, 0) + ?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, delta);
                ps.setLong(2, productId);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public Optional<Product> findByName(String name) {
        final String sql = "SELECT * FROM products WHERE name=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }
}
