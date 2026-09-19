package dao.jdbc;

import DataConnection.Db;
import dao.ProductUnitDAO;
import model.ProductUnit;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code product_units} okuyucusu. Yalnız SELECT çalıştırır; DDL veya
 * veri düzeltmesi yapmaz.
 */
public class ProductUnitJdbcDAO implements ProductUnitDAO {

    private static final String SELECT_ACTIVE =
            "SELECT id, code, display_name, display_order, is_active, created_at, updated_at " +
            "FROM product_units WHERE is_active = 1 " +
            "ORDER BY display_order, display_name";

    private final DataSource dataSource;

    public ProductUnitJdbcDAO() {
        this.dataSource = null;
    }

    public ProductUnitJdbcDAO(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Connection acquireConnection() throws SQLException {
        return dataSource == null ? Db.getConnection() : dataSource.getConnection();
    }

    @Override
    public List<ProductUnit> findActiveOrdered() {
        List<ProductUnit> out = new ArrayList<>();
        try (Connection c = acquireConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_ACTIVE);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return out;
    }

    private ProductUnit map(ResultSet rs) throws SQLException {
        ProductUnit u = new ProductUnit();
        u.setId(rs.getInt("id"));
        u.setCode(rs.getString("code"));
        u.setDisplayName(rs.getString("display_name"));
        u.setDisplayOrder(rs.getInt("display_order"));
        u.setActive(rs.getBoolean("is_active"));
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        if (created != null) {
            u.setCreatedAt(created.toLocalDateTime());
        }
        if (updated != null) {
            u.setUpdatedAt(updated.toLocalDateTime());
        }
        return u;
    }
}
