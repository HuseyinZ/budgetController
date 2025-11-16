package dao.jdbc;

import DataConnection.Db;
import dao.ProductDAO;
import model.Product;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class ProductJdbcDAO implements ProductDAO {

    private final DataSource dataSource;
    private final Connection externalConnection;
    private final Object priceColumnLock = new Object();
    private final Object stockColumnLock = new Object();
    private volatile boolean legacyPriceColumn;
    private volatile boolean legacyStockColumn;
    private volatile String stockColumnName = "stock_qty";

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
        applyProductName(p, rs);
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

        try {
            boolean active = rs.getBoolean("is_active");
            if (!rs.wasNull()) {
                p.setActive(active);
            }
        } catch (SQLException ignore) {
            // optional column
        }

        return p;
    }

    private void applyProductName(Product product, ResultSet rs) throws SQLException {
        String rawName = rs.getString("name");
        try {
            product.setName(rawName);
        } catch (IllegalArgumentException ex) {
            String fallback = fallbackProductName(product.getId(), rawName);
            System.err.println("Ürün adı geçersiz ('" + (rawName == null ? "" : rawName.trim())
                    + "'). '" + fallback + "' kullanılacak.");
            product.setName(fallback);
        }
    }

    private String fallbackProductName(Long id, String rawName) {
        if (rawName != null) {
            String trimmed = rawName.trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.length() > Product.NAME_MAX) {
                    return trimmed.substring(0, Product.NAME_MAX);
                }
                return trimmed;
            }
        }
        String suffix = (id == null || id <= 0) ? "?" : Long.toString(id);
        String fallback = "Ürün #" + suffix;
        if (fallback.length() > Product.NAME_MAX) {
            return fallback.substring(0, Product.NAME_MAX);
        }
        return fallback;
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
                            + ", " + stockColumn() + ", category_id) VALUES (?,?,?,?)";
                } else {
                    sql = "INSERT INTO products (name, " + priceColumn()
                            + ", category_id) VALUES (?,?,?)";
                }
                try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, e.getName());
                    ps.setBigDecimal(2, e.getUnitPrice());
                    int paramIndex = 3;
                    if (includeStock) {
                        int stock = e.getStock() == null ? 0 : Math.max(0, e.getStock());
                        ps.setInt(paramIndex, stock);
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
                if (!legacyStockColumn && handleMissingStock(ex, connection)) {
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
                            + "=?, " + stockColumn() + "=?, category_id=?, updated_at=NOW() WHERE id=?";
                } else {
                    sql = "UPDATE products SET name=?, " + priceColumn()
                            + "=?, category_id=?, updated_at=NOW() WHERE id=?";
                }
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, e.getName());
                    ps.setBigDecimal(2, e.getUnitPrice());
                    int paramIndex = 3;
                    if (includeStock) {
                        int stock = e.getStock() == null ? 0 : Math.max(0, e.getStock());
                        ps.setInt(paramIndex, stock);
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
                if (!legacyStockColumn && handleMissingStock(ex, connection)) {
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
    public List<Product> findByCategoryName(String categoryName) {
        final String sql = "SELECT p.* FROM products p " +
                "JOIN categories c ON c.id = p.category_id " +
                "WHERE LOWER(c.name) = LOWER(?) ORDER BY p.name";
        List<Product> list = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, categoryName);
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
        final String sql = "UPDATE products SET " + stockColumn()
                + " = COALESCE(" + stockColumn() + ", 0) + ?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, delta);
                ps.setLong(2, productId);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            if (!legacyStockColumn && handleMissingStock(ex, connection)) {
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

    private String stockColumn() {
        return stockColumnName;
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
            int stockValue = rs.getInt(stockColumn());
            if (rs.wasNull()) {
                return null;
            }
            return stockValue;
        } catch (SQLException ex) {
            if (handleMissingStock(ex, null)) {
                if (!legacyStockColumn) {
                    int stockValue = rs.getInt(stockColumn());
                    if (rs.wasNull()) {
                        return null;
                    }
                    return stockValue;
                }
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

    private boolean handleMissingStock(SQLException ex, Connection contextConnection) {
        if (!isMissingStock(ex)) {
            return false;
        }
        synchronized (stockColumnLock) {
            if (legacyStockColumn) {
                return true;
            }
            String missingColumn = stockColumnName;
            if (trySwitchStockColumn(missingColumn, "stock_qty", contextConnection, ex)) {
                return true;
            }
            if (trySwitchStockColumn(missingColumn, "stock", contextConnection, ex)) {
                return true;
            }
            if (!legacyStockColumn) {
                legacyStockColumn = true;
                System.err.println("Ürün tablosunda '" + missingColumn
                        + "' sütunu bulunamadı. Stok değerleri yok sayılacak. Ayrıntı: " + ex.getMessage());
            }
        }
        return true;
    }

    private boolean isMissingUnitPrice(SQLException ex) {
        SQLException current = ex;
        while (current != null) {
            String state = current.getSQLState();
            if ("42S22".equals(state) || "S0022".equals(state)) {
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
            if ("42S22".equals(state) || "S0022".equals(state)) {
                return true;
            }
            if (messageRefersMissingStock(current.getMessage())) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    private boolean trySwitchStockColumn(String missingColumn,
                                         String candidate,
                                         Connection contextConnection,
                                         SQLException detail) {
        if (candidate == null || candidate.equalsIgnoreCase(missingColumn)) {
            return false;
        }
        if (!hasColumn(candidate, contextConnection)) {
            return false;
        }
        stockColumnName = candidate;
        legacyStockColumn = false;
        System.err.println("Ürün tablosunda '" + missingColumn + "' sütunu bulunamadı. '"
                + candidate + "' sütunu kullanılacak. Ayrıntı: " + detail.getMessage());
        return true;
    }

    private boolean hasColumn(String columnName, Connection contextConnection) {
        if (columnName == null || columnName.isBlank()) {
            return false;
        }
        Connection connection = contextConnection;
        boolean close = false;
        if (connection == null) {
            if (externalConnection != null) {
                connection = externalConnection;
            } else if (dataSource != null) {
                try {
                    connection = dataSource.getConnection();
                    close = true;
                } catch (SQLException ex) {
                    return false;
                }
            } else {
                return false;
            }
        }
        try {
            DatabaseMetaData meta = connection.getMetaData();
            String catalog = safeCatalog(connection);
            String schema = safeSchema(connection);
            if (columnExists(meta, catalog, schema, "products", columnName)) {
                return true;
            }
            if (columnExists(meta, catalog, schema, "PRODUCTS", columnName)) {
                return true;
            }
            String upper = columnName.toUpperCase();
            if (!upper.equals(columnName)) {
                if (columnExists(meta, catalog, schema, "products", upper)
                        || columnExists(meta, catalog, schema, "PRODUCTS", upper)) {
                    return true;
                }
            }
            String lower = columnName.toLowerCase();
            if (!lower.equals(columnName)) {
                if (columnExists(meta, catalog, schema, "products", lower)
                        || columnExists(meta, catalog, schema, "PRODUCTS", lower)) {
                    return true;
                }
            }
        } catch (SQLException | RuntimeException | AbstractMethodError ignore) {
            return false;
        } finally {
            if (close && connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignore) {
                    // ignore
                }
            }
        }
        return false;
    }

    private boolean columnExists(DatabaseMetaData meta,
                                 String catalog,
                                 String schema,
                                 String table,
                                 String column) throws SQLException {
        if (meta == null || column == null) {
            return false;
        }
        try (ResultSet rs = meta.getColumns(catalog, schema, table, column)) {
            return rs.next();
        }
    }

    private String safeCatalog(Connection connection) {
        if (connection == null) {
            return null;
        }
        try {
            return connection.getCatalog();
        } catch (SQLException ex) {
            return null;
        }
    }

    private String safeSchema(Connection connection) {
        if (connection == null) {
            return null;
        }
        try {
            return connection.getSchema();
        } catch (SQLException | AbstractMethodError ex) {
            return null;
        }
    }

    private boolean messageRefersMissingUnitPrice(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (!lower.contains("unit_price")) {
            return false;
        }
        if (lower.contains("unknown column")) {
            return true;
        }
        if (lower.contains("not found") || lower.contains("doesn't exist") || lower.contains("does not exist")) {
            return true;
        }
        return lower.contains("bulunamad");
    }

    private boolean messageRefersMissingStock(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (!lower.contains("stock")) {
            return false;
        }
        if (lower.contains("unknown column")) {
            return true;
        }
        if (lower.contains("not found") || lower.contains("doesn't exist") || lower.contains("does not exist")) {
            return true;
        }
        return lower.contains("bulunamad");
    }
}
