package dao.jdbc;

import DataConnection.Db;
import dao.ProductDAO;
import model.Product;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ProductJdbcDAO implements ProductDAO {

    private final DataSource dataSource;
    private final Connection externalConnection;
    private final Object priceColumnLock = new Object();
    private final Object stockColumnLock = new Object();
    private volatile boolean legacyPriceColumn;
    private volatile boolean legacyStockColumn;

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
        BigDecimal price = readUnitPrice(rs);
        if (price != null) {
            p.setUnitPrice(price);
        }

        p.setStock(readStock(rs));

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
        for (int attempt = 0; attempt < 2; attempt++) {
            Connection connection = null;
            try {
                connection = acquireConnection();
                final boolean includeStock = !legacyStockColumn;
                final String sql;
                if (includeStock) {
                    sql = "INSERT INTO products (name, " + priceColumn()
                            + ", stock, category_id) VALUES (?,?,?,?)";
                } else {
                    sql = "INSERT INTO products (name, " + priceColumn()
                            + ", category_id) VALUES (?,?,?)";
                }
                try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, e.getName());
                    ps.setBigDecimal(2, e.getUnitPrice());
                    int paramIndex = 3;
                    if (includeStock) {
                        if (e.getStock() == null) {
                            ps.setNull(paramIndex, Types.INTEGER);
                        } else {
                            ps.setInt(paramIndex, e.getStock());
                        }
                        paramIndex++;
                    }
                    if (e.getCategoryId() == null) {
                        ps.setNull(paramIndex, Types.BIGINT);
                    } else {
                        ps.setLong(paramIndex, e.getCategoryId());
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
                boolean handled = false;
                if (!legacyPriceColumn && handleMissingUnitPrice(ex)) {
                    handled = true;
                }
                if (!legacyStockColumn && handleMissingStock(ex)) {
                    handled = true;
                }
                if (handled) {
                    continue;
                }
                throw new RuntimeException(ex);
            } finally {
                close(connection);
            }
        }
        throw new IllegalStateException("Ürün fiyat sütunu bulunamadı");
    }

    @Override
    public void update(Product e) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Connection connection = null;
            try {
                connection = acquireConnection();
                final boolean includeStock = !legacyStockColumn;
                final String sql;
                if (includeStock) {
                    sql = "UPDATE products SET name=?, " + priceColumn()
                            + "=?, stock=?, category_id=?, updated_at=NOW() WHERE id=?";
                } else {
                    sql = "UPDATE products SET name=?, " + priceColumn()
                            + "=?, category_id=?, updated_at=NOW() WHERE id=?";
                }
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, e.getName());
                    ps.setBigDecimal(2, e.getUnitPrice());
                    int paramIndex = 3;
                    if (includeStock) {
                        if (e.getStock() == null) {
                            ps.setNull(paramIndex, Types.INTEGER);
                        } else {
                            ps.setInt(paramIndex, e.getStock());
                        }
                        paramIndex++;
                    }
                    if (e.getCategoryId() == null) {
                        ps.setNull(paramIndex, Types.BIGINT);
                    } else {
                        ps.setLong(paramIndex, e.getCategoryId());
                    }
                    paramIndex++;
                    ps.setLong(paramIndex, e.getId());
                    ps.executeUpdate();
                    return;
                }
            } catch (SQLException ex) {
                boolean handled = false;
                if (!legacyPriceColumn && handleMissingUnitPrice(ex)) {
                    handled = true;
                }
                if (!legacyStockColumn && handleMissingStock(ex)) {
                    handled = true;
                }
                if (handled) {
                    continue;
                }
                throw new RuntimeException(ex);
            } finally {
                close(connection);
            }
        }
        throw new IllegalStateException("Ürün fiyat sütunu bulunamadı");
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
        if (legacyStockColumn) {
            return;
        }
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
            if (!legacyStockColumn && handleMissingStock(ex)) {
                return;
            }
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

    private String priceColumn() {
        return legacyPriceColumn ? "price" : "unit_price";
    }

    private BigDecimal readUnitPrice(ResultSet rs) throws SQLException {
        try {
            return rs.getBigDecimal(priceColumn());
        } catch (SQLException ex) {
            if (!legacyPriceColumn && handleMissingUnitPrice(ex)) {
                return rs.getBigDecimal(priceColumn());
            }
            throw ex;
        }
    }

    private Integer readStock(ResultSet rs) throws SQLException {
        if (legacyStockColumn) {
            return null;
        }
        try {
            int stockValue = rs.getInt("stock");
            if (rs.wasNull()) {
                return null;
            }
            return stockValue;
        } catch (SQLException ex) {
            if (!legacyStockColumn && handleMissingStock(ex)) {
                return null;
            }
            throw ex;
        }
    }

    private boolean handleMissingUnitPrice(SQLException ex) {
        if (!isMissingUnitPrice(ex)) {
            return false;
        }
        synchronized (priceColumnLock) {
            if (!legacyPriceColumn) {
                legacyPriceColumn = true;
                System.err.println("Ürün tablosunda 'unit_price' sütunu bulunamadı. 'price' sütunu kullanılacak. Ayrıntı: "
                        + ex.getMessage());
            }
        }
        return true;
    }

    private boolean handleMissingStock(SQLException ex) {
        if (!isMissingStock(ex)) {
            return false;
        }
        synchronized (stockColumnLock) {
            if (!legacyStockColumn) {
                legacyStockColumn = true;
                System.err.println("Ürün tablosunda 'stock' sütunu bulunamadı. Stok değerleri yok sayılacak. Ayrıntı: "
                        + ex.getMessage());
            }
        }
        return true;
    }

    private boolean isMissingUnitPrice(SQLException ex) {
        SQLException current = ex;
        while (current != null) {
            String state = current.getSQLState();
            if ("42S22".equals(state)) {
                return true;
            }
            if (messageRefersMissingUnitPrice(current.getMessage())) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    private boolean isMissingStock(SQLException ex) {
        SQLException current = ex;
        while (current != null) {
            String state = current.getSQLState();
            if ("42S22".equals(state)) {
                return true;
            }
            if (messageRefersMissingStock(current.getMessage())) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    private boolean messageRefersMissingUnitPrice(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("unknown column") && lower.contains("unit_price");
    }

    private boolean messageRefersMissingStock(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("unknown column") && lower.contains("stock");
    }
}
