package dao.jdbc;

import DataConnection.Db;
import dao.OrderItemsDAO;
import model.OrderItem;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class OrderItemsJdbcDAO implements OrderItemsDAO {

    private final DataSource dataSource;
    private final Connection externalConnection;

    public OrderItemsJdbcDAO() {
        this(Db.getDataSource(), null);
    }

    public OrderItemsJdbcDAO(DataSource dataSource) {
        this(dataSource, null);
    }

    public OrderItemsJdbcDAO(Connection connection) {
        this(Db.getDataSource(), connection);
    }

    private OrderItemsJdbcDAO(DataSource dataSource, Connection externalConnection) {
        this.dataSource = dataSource;
        this.externalConnection = externalConnection;
    }

    private Connection acquireConnection() throws SQLException {
        if (externalConnection != null) {
            return externalConnection;
        }
        if (dataSource == null) {
            throw new IllegalStateException("No DataSource configured for OrderItemsJdbcDAO");
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

    private OrderItem map(ResultSet rs) throws SQLException {
        OrderItem it = new OrderItem();
        it.setId(rs.getLong("id"));
        it.setOrderId(rs.getLong("order_id"));
        it.setProductId(rs.getLong("product_id"));
        it.setQuantity(rs.getInt("quantity"));
        it.setUnitPrice(rs.getBigDecimal("unit_price"));
        try { it.setProductName(rs.getString("product_name")); } catch (SQLException ignore) {}
        try { it.setNetAmount(rs.getBigDecimal("net_amount")); } catch (SQLException ignore) {}
        try { it.setTaxAmount(rs.getBigDecimal("tax_amount")); } catch (SQLException ignore) {}
        try { it.setLineTotal(rs.getBigDecimal("line_total")); } catch (SQLException ignore) {}
        try {
            int v = rs.getInt("kitchen_override_id");
            it.setKitchenOverrideId(rs.wasNull() ? null : v);
        } catch (SQLException ignore) {}
        try {
            int pp = rs.getInt("pieces_per_portion");
            it.setPiecesPerPortion(rs.wasNull() ? null : pp);
        } catch (SQLException ignore) {}
        try {
            String lbl = rs.getString("unit_label");
            it.setUnitLabel(lbl);
        } catch (SQLException ignore) {}
        try {
            Timestamp pa = rs.getTimestamp("printed_at");
            if (pa != null) it.setPrintedAt(pa.toLocalDateTime());
        } catch (SQLException ignore) {}
        try {
            String n = rs.getString("note");
            if (n != null) it.setNote(n);
        } catch (SQLException ignore) {}

        try {
            Timestamp c = rs.getTimestamp("created_at");
            Timestamp u = rs.getTimestamp("updated_at");
            if (c != null) it.setCreatedAt(c.toLocalDateTime());
            if (u != null) it.setUpdatedAt(u.toLocalDateTime());
        } catch (SQLException ignore) {}

        return it;
    }

    @Override
    public Long create(OrderItem e) {
        final String sql = "INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_price) VALUES (?,?,?,?,?)";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, e.getOrderId());
                ps.setLong(2, e.getProductId());
                if (e.getProductName() == null || e.getProductName().isBlank()) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, e.getProductName());
                }
                ps.setInt(4, e.getQuantity());
                ps.setBigDecimal(5, e.getUnitPrice());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
                throw new SQLException("No generated key for order_items");
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public void update(OrderItem e) {
        final String sql = "UPDATE order_items SET quantity=?, unit_price=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, e.getQuantity());
                ps.setBigDecimal(2, e.getUnitPrice());
                ps.setLong(3, e.getId());
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
        final String sql = "DELETE FROM order_items WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
    public Optional<OrderItem> findById(Long id) {
        final String sql = "SELECT * FROM order_items WHERE id=?";
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
    public List<OrderItem> findAll(int offset, int limit) {
        final String sql = "SELECT * FROM order_items ORDER BY id LIMIT ? OFFSET ?";
        List<OrderItem> list = new ArrayList<>();
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
    public List<OrderItem> findByOrderId(Long orderId) {
        final String sql = "SELECT * FROM order_items WHERE order_id=? ORDER BY id";
        List<OrderItem> list = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, orderId);
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
    public void addOrIncrement(Long orderId, Long productId, String productName, int quantity, BigDecimal unitPrice) {
        addOrIncrement(orderId, productId, productName, quantity, unitPrice, null, null);
    }

    @Override
    public void addOrIncrement(Long orderId, Long productId, String productName, int quantity,
                               BigDecimal unitPrice, Integer piecesPerPortion, String unitLabel) {
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement upd = connection.prepareStatement(
                    "UPDATE order_items SET quantity = quantity + ?, unit_price = ? WHERE order_id=? AND product_id=?")) {
                upd.setInt(1, quantity);
                upd.setBigDecimal(2, unitPrice);
                upd.setLong(3, orderId);
                upd.setLong(4, productId);
                int rows = upd.executeUpdate();

                if (rows == 0) {
                    try (PreparedStatement ins = connection.prepareStatement(
                            "INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_price) VALUES (?,?,?,?,?)")) {
                        ins.setLong(1, orderId);
                        ins.setLong(2, productId);
                        if (productName == null || productName.isBlank()) {
                            ins.setNull(3, Types.VARCHAR);
                        } else {
                            ins.setString(3, productName);
                        }
                        ins.setInt(4, quantity);
                        ins.setBigDecimal(5, unitPrice);
                        ins.executeUpdate();
                    }
                    // Snapshot alanları varsa atayalım (best-effort)
                    if (piecesPerPortion != null || unitLabel != null) {
                        applyPortionSnapshotBestEffort(connection, orderId, productId,
                                piecesPerPortion, unitLabel);
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    /**
     * Yeni eklenen order_items satırına pieces_per_portion + unit_label
     * snapshot'ı yazmak için best-effort UPDATE. Sütunlar yoksa sessiz geç.
     */
    private void applyPortionSnapshotBestEffort(Connection connection,
                                                Long orderId, Long productId,
                                                Integer piecesPerPortion, String unitLabel) {
        if (connection == null) return;
        final String sql = "UPDATE order_items SET pieces_per_portion=?, unit_label=? " +
                "WHERE order_id=? AND product_id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (piecesPerPortion == null) ps.setNull(1, Types.INTEGER);
            else ps.setInt(1, piecesPerPortion);
            if (unitLabel == null || unitLabel.isBlank()) ps.setNull(2, Types.VARCHAR);
            else ps.setString(2, unitLabel);
            ps.setLong(3, orderId);
            ps.setLong(4, productId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            // Migration v3 uygulanmadıysa sütun yoktur — sessiz geç.
        }
    }

    @Override
    public void decrementOrRemove(Long orderItemId, int quantity) {
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM order_items WHERE id=? AND quantity <= ?")) {
                delete.setLong(1, orderItemId);
                delete.setInt(2, quantity);
                int removed = delete.executeUpdate();
                if (removed > 0) {
                    return;
                }
            }

            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE order_items SET quantity = quantity - ? WHERE id=? AND quantity > ?")) {
                update.setInt(1, quantity);
                update.setLong(2, orderItemId);
                update.setInt(3, quantity);
                update.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public void updateKitchenOverride(Long orderItemId, Integer printerId) {
        final String sql = "UPDATE order_items SET kitchen_override_id=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                if (printerId == null) {
                    ps.setNull(1, Types.INTEGER);
                } else {
                    ps.setInt(1, printerId);
                }
                ps.setLong(2, orderItemId);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public void updateNote(Long orderItemId, String note) {
        final String sql = "UPDATE order_items SET note=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                if (note == null || note.isBlank()) ps.setNull(1, Types.VARCHAR);
                else ps.setString(1, note.length() > 255 ? note.substring(0, 255) : note);
                ps.setLong(2, orderItemId);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            // Migration uygulanmadıysa note sütunu yoktur — sessiz geç
        } finally {
            close(connection);
        }
    }

    @Override
    public int markItemsPrinted(Long orderId) {
        // Sadece henüz basılmamış kalemleri işaretle (idempotent)
        final String sql = "UPDATE order_items " +
                "SET printed_at = NOW(), print_count = COALESCE(print_count, 0) + 1, " +
                "    updated_at = NOW() " +
                "WHERE order_id = ? AND printed_at IS NULL";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, orderId);
                return ps.executeUpdate();
            }
        } catch (SQLException ex) {
            // Migration uygulanmadıysa printed_at sütunu yoktur — sessiz geç
            return 0;
        } finally {
            close(connection);
        }
    }

    @Override
    public void removeAllForOrder(Long orderId) {
        final String sql = "DELETE FROM order_items WHERE order_id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, orderId);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }
}
