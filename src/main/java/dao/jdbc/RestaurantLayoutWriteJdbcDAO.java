package dao.jdbc;

import dao.RestaurantLayoutWriteDAO;
import model.RestaurantArea;
import model.TableLayoutEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link RestaurantLayoutWriteDAO} JDBC uygulaması.
 *
 * <p>Bağlantıyı AÇMAZ ve KAPATMAZ, commit/rollback yapmaz: transaction sınırı
 * servis katmanına aittir. Hiçbir DDL çalıştırmaz, hiçbir satırı silmez.
 */
public class RestaurantLayoutWriteJdbcDAO implements RestaurantLayoutWriteDAO {

    @Override
    public int insertArea(Connection conn, RestaurantArea area) {
        final String sql = "INSERT INTO restaurant_areas "
                + "(building, floor, salon, display_order, is_active) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, area.getBuilding());
            ps.setString(2, area.getFloor());
            ps.setString(3, area.getSalon());
            ps.setInt(4, area.getDisplayOrder());
            ps.setBoolean(5, area.isActive());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
            throw new SQLException("Alan kimliği üretilemedi", "HY000");
        } catch (SQLException ex) {
            throw wrap(ex);
        }
    }

    @Override
    public void updateAreaNamesAndOrder(Connection conn, RestaurantArea area) {
        final String sql = "UPDATE restaurant_areas SET building = ?, floor = ?, salon = ?, "
                + "display_order = ?, updated_at = NOW() WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, area.getBuilding());
            ps.setString(2, area.getFloor());
            ps.setString(3, area.getSalon());
            ps.setInt(4, area.getDisplayOrder());
            ps.setInt(5, area.getId() == null ? 0 : area.getId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw wrap(ex);
        }
    }

    @Override
    public void setAreaActive(Connection conn, int areaId, boolean active) {
        final String sql = "UPDATE restaurant_areas SET is_active = ?, updated_at = NOW() WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, active);
            ps.setInt(2, areaId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw wrap(ex);
        }
    }

    @Override
    public Optional<RestaurantArea> findAreaById(Connection conn, int areaId) {
        final String sql = "SELECT id, building, floor, salon, display_order, is_active "
                + "FROM restaurant_areas WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, areaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapArea(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw wrap(ex);
        }
    }

    @Override
    public List<RestaurantArea> findAllAreasOrdered(Connection conn) {
        final String sql = "SELECT id, building, floor, salon, display_order, is_active "
                + "FROM restaurant_areas ORDER BY display_order, id";
        List<RestaurantArea> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapArea(rs));
            }
        } catch (SQLException ex) {
            throw wrap(ex);
        }
        return out;
    }

    @Override
    public List<TableLayoutEntry> findAllTablesOrdered(Connection conn) {
        final String sql = "SELECT table_no, area_id, pos_x, pos_y, width, height, shape, "
                + "rotation_deg, display_order, is_active FROM restaurant_table_layout "
                + "ORDER BY display_order, table_no";
        List<TableLayoutEntry> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapTable(rs));
            }
        } catch (SQLException ex) {
            throw wrap(ex);
        }
        return out;
    }

    @Override
    public boolean areaKeyExists(Connection conn, String building, String floor, String salon,
                                 Integer exceptAreaId) {
        final String sql = "SELECT COUNT(*) FROM restaurant_areas "
                + "WHERE building = ? AND floor = ? AND salon = ? AND (? IS NULL OR id <> ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, building);
            ps.setString(2, floor);
            ps.setString(3, salon);
            if (exceptAreaId == null) {
                ps.setNull(4, Types.INTEGER);
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(4, exceptAreaId);
                ps.setInt(5, exceptAreaId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException ex) {
            throw wrap(ex);
        }
    }

    @Override
    public void insertTable(Connection conn, TableLayoutEntry t) {
        final String sql = "INSERT INTO restaurant_table_layout "
                + "(table_no, area_id, pos_x, pos_y, width, height, shape, rotation_deg, "
                + "display_order, is_active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, t.getTableNo());
            ps.setInt(2, t.getAreaId());
            setNullableInt(ps, 3, t.getPosX());
            setNullableInt(ps, 4, t.getPosY());
            setNullableInt(ps, 5, t.getWidth());
            setNullableInt(ps, 6, t.getHeight());
            ps.setString(7, t.getShape());
            ps.setInt(8, t.getRotationDeg());
            ps.setInt(9, t.getDisplayOrder());
            ps.setBoolean(10, t.isActive());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw wrap(ex);
        }
    }

    @Override
    public void setTableActive(Connection conn, int tableNo, boolean active) {
        final String sql = "UPDATE restaurant_table_layout SET is_active = ?, updated_at = NOW() "
                + "WHERE table_no = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, active);
            ps.setInt(2, tableNo);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw wrap(ex);
        }
    }

    @Override
    public void updatePlacement(Connection conn, TableLayoutEntry t) {
        // table_no ve area_id bilinçli olarak güncellenmez: numara değişmez,
        // alan taşıma ayrı bir iş kalemidir.
        final String sql = "UPDATE restaurant_table_layout SET pos_x = ?, pos_y = ?, width = ?, "
                + "height = ?, shape = ?, rotation_deg = ?, display_order = ?, updated_at = NOW() "
                + "WHERE table_no = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            setNullableInt(ps, 1, t.getPosX());
            setNullableInt(ps, 2, t.getPosY());
            setNullableInt(ps, 3, t.getWidth());
            setNullableInt(ps, 4, t.getHeight());
            ps.setString(5, t.getShape());
            ps.setInt(6, t.getRotationDeg());
            ps.setInt(7, t.getDisplayOrder());
            ps.setInt(8, t.getTableNo());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw wrap(ex);
        }
    }

    @Override
    public Optional<TableLayoutEntry> findTableByNo(Connection conn, int tableNo) {
        final String sql = "SELECT table_no, area_id, pos_x, pos_y, width, height, shape, "
                + "rotation_deg, display_order, is_active FROM restaurant_table_layout WHERE table_no = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tableNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapTable(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw wrap(ex);
        }
    }

    @Override
    public List<TableLayoutEntry> findTablesByArea(Connection conn, int areaId, boolean activeOnly) {
        String sql = "SELECT table_no, area_id, pos_x, pos_y, width, height, shape, rotation_deg, "
                + "display_order, is_active FROM restaurant_table_layout WHERE area_id = ?";
        if (activeOnly) {
            sql += " AND is_active = 1";
        }
        sql += " ORDER BY display_order, table_no";
        List<TableLayoutEntry> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, areaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapTable(rs));
                }
            }
        } catch (SQLException ex) {
            throw wrap(ex);
        }
        return out;
    }

    // ------------------------------------------------------------------

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static RestaurantArea mapArea(ResultSet rs) throws SQLException {
        RestaurantArea a = new RestaurantArea();
        a.setId(rs.getInt("id"));
        a.setBuilding(rs.getString("building"));
        a.setFloor(rs.getString("floor"));
        a.setSalon(rs.getString("salon"));
        a.setDisplayOrder(rs.getInt("display_order"));
        a.setActive(rs.getBoolean("is_active"));
        return a;
    }

    private static TableLayoutEntry mapTable(ResultSet rs) throws SQLException {
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
        return t;
    }

    /** SQL hatası — mesaj/stack trace değil, yalnız SQLState + vendorCode taşınır. */
    private static RuntimeException wrap(SQLException ex) {
        return new RuntimeException("Masa düzeni yazma hatası (SQLState=" + ex.getSQLState()
                + ", vendorCode=" + ex.getErrorCode() + ")", ex);
    }
}
