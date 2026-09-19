package dao.jdbc;

import DataConnection.Db;
import dao.RestaurantLayoutDAO;
import model.RestaurantArea;
import model.TableLayoutEntry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Masa düzeni okuyucusu. Yalnız SELECT çalıştırır: DDL, INSERT/UPDATE/DELETE
 * veya "eksikse oluştur" davranışı YOKTUR.
 */
public class RestaurantLayoutJdbcDAO implements RestaurantLayoutDAO {

    static final String SELECT_AREAS =
            "SELECT id, building, floor, salon, display_order, is_active " +
            "FROM restaurant_areas WHERE is_active = 1 " +
            "ORDER BY display_order, id";

    static final String SELECT_TABLES =
            "SELECT table_no, area_id, pos_x, pos_y, width, height, shape, rotation_deg, " +
            "display_order, is_active " +
            "FROM restaurant_table_layout WHERE is_active = 1 " +
            "ORDER BY display_order, table_no";

    private final DataSource dataSource;

    public RestaurantLayoutJdbcDAO() {
        this.dataSource = null;
    }

    public RestaurantLayoutJdbcDAO(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Connection acquireConnection() throws SQLException {
        return dataSource == null ? Db.getConnection() : dataSource.getConnection();
    }

    @Override
    public List<RestaurantArea> findActiveAreasOrdered() {
        List<RestaurantArea> out = new ArrayList<>();
        try (Connection c = acquireConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_AREAS);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                RestaurantArea a = new RestaurantArea();
                a.setId(rs.getInt("id"));
                a.setBuilding(rs.getString("building"));
                a.setFloor(rs.getString("floor"));
                a.setSalon(rs.getString("salon"));
                a.setDisplayOrder(rs.getInt("display_order"));
                a.setActive(rs.getBoolean("is_active"));
                out.add(a);
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return out;
    }

    @Override
    public List<TableLayoutEntry> findActiveTablesOrdered() {
        List<TableLayoutEntry> out = new ArrayList<>();
        try (Connection c = acquireConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_TABLES);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TableLayoutEntry t = new TableLayoutEntry();
                t.setTableNo(rs.getInt("table_no"));
                t.setAreaId(rs.getInt("area_id"));
                t.setPosX(nullableInt(rs, "pos_x"));
                t.setPosY(nullableInt(rs, "pos_y"));
                t.setWidth(nullableInt(rs, "width"));
                t.setHeight(nullableInt(rs, "height"));
                t.setShape(rs.getString("shape"));
                t.setRotationDeg(rs.getInt("rotation_deg"));
                t.setDisplayOrder(rs.getInt("display_order"));
                t.setActive(rs.getBoolean("is_active"));
                out.add(t);
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
        return out;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
