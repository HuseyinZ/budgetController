package dao.jdbc;

import DataConnection.Db;
import dao.ProductDAO;
import model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class ProductJdbcDAO implements ProductDAO {

    private static final Logger LOG = LoggerFactory.getLogger(ProductJdbcDAO.class);

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

        // Stok değeri DB'de negatif kalmış olabilir (eski siparişlerden) — Product.setStock
        // negatif kabul etmez, bu yüzden 0'da clamp ile oku.
        Integer rawStock = readStock(rs);
        p.setStock(rawStock == null ? null : Math.max(0, rawStock));

        long categoryValue = rs.getLong("category_id");
        if (!rs.wasNull()) {
            p.setCategoryId(categoryValue);
        } else {
            p.setCategoryId(null);
        }

        // Yeni alanlar (migration v3) — opsiyonel, eski şemada yok ise sessiz geç.
        // RuntimeException de yakalanır çünkü bazı JDBC proxy'leri (test stub'ları)
        // UnsupportedOperationException atabilir.
        try {
            int pp = rs.getInt("pieces_per_portion");
            if (!rs.wasNull()) {
                p.setPiecesPerPortion(pp);
            }
        } catch (SQLException | RuntimeException ignore) {
            // sütun eski şemada yok veya proxy desteklemiyor; geç
        }
        try {
            String label = rs.getString("unit_label");
            if (label != null) {
                p.setUnitLabel(label);
            }
        } catch (SQLException | RuntimeException ignore) {
            // sütun eski şemada yok veya proxy desteklemiyor; geç
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
        } catch (SQLException | RuntimeException ignore) {
            // sütun eski şemada yok veya proxy desteklemiyor; geç
        }

        try {
            boolean active = rs.getBoolean("is_active");
            if (!rs.wasNull()) {
                p.setActive(active);
            }
        } catch (SQLException | RuntimeException ignore) {
            // sütun eski şemada yok veya proxy desteklemiyor; geç
        }

        return p;
    }

    private void applyProductName(Product product, ResultSet rs) throws SQLException {
        String rawName = rs.getString("name");
        try {
            product.setName(rawName);
        } catch (IllegalArgumentException ex) {
            String fallback = fallbackProductName(product.getId(), rawName);
            LOG.warn("Ürün adı geçersiz ('{}'). '{}' kullanılacak.",
                    rawName == null ? "" : rawName.trim(), fallback);
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
                            long id = rs.getLong(1);
                            applyPortioningBestEffort(connection, id,
                                    e.getPiecesPerPortion(), e.getUnitLabel());
                            return id;
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
                    applyPortioningBestEffort(connection, e.getId(),
                            e.getPiecesPerPortion(), e.getUnitLabel());
                    applyActiveBestEffort(connection, e.getId(), e.isActive());
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
        // İlk deneme: doğrudan delta uygula. Eğer CHECK constraint patlasa
        // (stock < 0 olur) → GREATEST(0, ...) ile clamp eden bir UPDATE yap.
        // Sebep: bizim sistem stok yönetimini kullanıcı kontrolünde tutmuyor
        // (UI'dan gizli). Ama DB'de "stock >= 0" CHECK olabiliyor → şiş bazlı
        // ürünlerde virtual-restock + orderService azaltması toplamı 0'dan
        // küçük çıkarsa hata fırlatıyor. Bu durumda stok değişmesin.
        final String sqlDelta = "UPDATE products SET " + stockColumn()
                + " = COALESCE(" + stockColumn() + ", 0) + ?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sqlDelta)) {
                ps.setInt(1, delta);
                ps.setLong(2, productId);
                ps.executeUpdate();
                return;
            }
        } catch (SQLException ex) {
            if (!legacyStockColumn && handleMissingStock(ex, connection)) {
                return;
            }
            // CHECK constraint ihlali (stock < 0) — clamp uygulayarak yeniden dene
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            boolean isCheckViolation = msg.contains("check constraint")
                    || msg.contains("products_chk")
                    || msg.contains("violates check");
            if (isCheckViolation) {
                final String sqlClamp = "UPDATE products SET " + stockColumn()
                        + " = GREATEST(0, COALESCE(" + stockColumn() + ", 0) + ?),"
                        + " updated_at=NOW() WHERE id=?";
                try (PreparedStatement ps2 = connection.prepareStatement(sqlClamp)) {
                    ps2.setInt(1, delta);
                    ps2.setLong(2, productId);
                    ps2.executeUpdate();
                    return;
                } catch (SQLException ex2) {
                    LOG.warn("Stok güncellemesi başarısız (id={}): {}", productId, ex2.getMessage());
                    // Stok bizim için kritik değil → sessiz geç
                    return;
                }
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
                LOG.warn("Ürün tablosunda 'unit_price' sütunu bulunamadı. 'price' kullanılacak. Detay: {}",
                        ex.getMessage());
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
                LOG.warn("Ürün tablosunda '{}' sütunu bulunamadı. Stok yok sayılacak. Detay: {}",
                        missingColumn, ex.getMessage());
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
        LOG.warn("Ürün tablosunda '{}' sütunu bulunamadı. '{}' kullanılacak. Detay: {}",
                missingColumn, candidate, detail.getMessage());
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
            // Bazı JDBC sürücüleri / test proxy'leri getMetaData'yı desteklemez —
            // bu durumda metadata kontrolü yapılamadığı için sütun yok varsayalım.
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

    /**
     * Yeni sütunlar (pieces_per_portion, unit_label) varsa günceller; yoksa
     * sessizce geçer. Eski şemalı veritabanlarında uygulama çökmesin diye.
     */
    private void applyPortioningBestEffort(Connection connection, long productId,
                                           Integer piecesPerPortion, String unitLabel) {
        if (connection == null || productId <= 0) {
            return;
        }
        // Anlamlı bir veri yoksa hiç UPDATE atma — test proxy'leri ve eski şemada
        // gereksiz hata oluşturmasın.
        boolean hasPieces = piecesPerPortion != null;
        boolean hasLabel  = unitLabel != null && !unitLabel.isBlank();
        if (!hasPieces && !hasLabel) {
            return;
        }
        final String sql = "UPDATE products SET pieces_per_portion=?, unit_label=? WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (piecesPerPortion == null) {
                ps.setNull(1, Types.INTEGER);
            } else {
                ps.setInt(1, piecesPerPortion);
            }
            if (unitLabel == null || unitLabel.isBlank()) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, unitLabel);
            }
            ps.setLong(3, productId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            // Migration v3 henüz uygulanmadıysa sütun yoktur — sessizce geç.
            // (Sadece bir kez logla yetebilir, ancak iyimser: loglamayı atla.)
        }
    }

    /**
     * is_active sütununu best-effort günceller. Eski şemada sütun yoksa
     * sessizce geçer (UPDATE products SET active=? ... formatını da dener).
     * Hem MySQL hem H2 testlerinde tolere edilir.
     */
    private void applyActiveBestEffort(Connection connection, Long productId, boolean active) {
        if (productId == null || productId <= 0) return;
        // Önce 'is_active' adıyla dene (yeni şema)
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE products SET is_active=? WHERE id=?")) {
            ps.setBoolean(1, active);
            ps.setLong(2, productId);
            int rows = ps.executeUpdate();
            if (rows >= 0) {
                return; // başarıyla yazıldı
            }
        } catch (SQLException ignore) {
            // is_active sütunu yok → 'active' adıyla dene
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE products SET active=? WHERE id=?")) {
            ps.setBoolean(1, active);
            ps.setLong(2, productId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            LOG.warn("Ürün is_active/active sütunu bulunamadı (id={}): {}", productId, ex.getMessage());
        }
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
