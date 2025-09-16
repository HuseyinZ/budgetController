package dao.jdbc;

import DataConnection.Db;
import dao.RestaurantTableDAO;
import model.RestaurantTable;
import model.TableStatus;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RestaurantTableJdbcDAO implements RestaurantTableDAO {

    private final DataSource dataSource;
    private final Connection externalConnection;

    public RestaurantTableJdbcDAO() {
        this(Db.getDataSource(), null);
    }

    public RestaurantTableJdbcDAO(DataSource dataSource) {
        this(dataSource, null);
    }

    public RestaurantTableJdbcDAO(Connection connection) {
        this(Db.getDataSource(), connection);
    }

    private RestaurantTableJdbcDAO(DataSource dataSource, Connection externalConnection) {
        this.dataSource = dataSource;
        this.externalConnection = externalConnection;
    }

    private Connection acquireConnection() throws SQLException {
        if (externalConnection != null) {
            return externalConnection;
        }
        if (dataSource == null) {
            throw new IllegalStateException("No DataSource configured for RestaurantTableJdbcDAO");
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

    private RestaurantTable map(ResultSet rs) throws SQLException {
        RestaurantTable t = new RestaurantTable();
        t.setId(rs.getLong("id"));
        t.setTableNo(rs.getInt("table_no"));
        t.setStatus(TableStatus.valueOf(rs.getString("status")));
        t.setNote(rs.getString("note"));

        try {
            Timestamp c = rs.getTimestamp("created_at");
            Timestamp u = rs.getTimestamp("updated_at");
            if (c != null) t.setCreatedAt(c.toLocalDateTime());
            if (u != null) t.setUpdatedAt(u.toLocalDateTime());
        } catch (SQLException ignore) {}

        return t;
    }

    @Override
    public Long create(RestaurantTable e) {
        final String sql = "INSERT INTO dining_tables (table_no, status, note) VALUES (?,?,?)";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, e.getTableNo());
                ps.setString(2, e.getStatus().name());
                ps.setString(3, e.getNote());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getLong(1);
                }
                throw new SQLException("No generated key for dining_tables");
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public void update(RestaurantTable e) {
        final String sql = "UPDATE dining_tables SET table_no=?, status=?, note=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, e.getTableNo());
                ps.setString(2, e.getStatus().name());
                ps.setString(3, e.getNote());
                ps.setLong(4, e.getId());
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
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM dining_tables WHERE id=?")) {
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
    public Optional<RestaurantTable> findById(Long id) {
        final String sql = "SELECT id, table_no, status, note FROM dining_tables WHERE id=?";
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
    public Optional<RestaurantTable> findByTableNo(int tableNo) {
        final String sql = "SELECT id, table_no, status, note FROM dining_tables WHERE table_no=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, tableNo);
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
    public List<RestaurantTable> findAll(int offset, int limit) {
        final String sql = "SELECT id, table_no, status, note FROM dining_tables ORDER BY table_no LIMIT ? OFFSET ?";
        List<RestaurantTable> out = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(map(rs));
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
        return out;
    }

    @Override
    public List<RestaurantTable> findByStatus(TableStatus status) {
        final String sql = "SELECT id, table_no, status, note FROM dining_tables WHERE status=? ORDER BY table_no";
        List<RestaurantTable> out = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, status.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(map(rs));
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
        return out;
    }

    @Override
    public void updateStatus(Long tableId, TableStatus status) {
        final String sql = "UPDATE dining_tables SET status=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, status.name());
                ps.setLong(2, tableId);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public void setNote(Long tableId, String note) {
        final String sql = "UPDATE dining_tables SET note=?, updated_at=NOW() WHERE id=?";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, note);
                ps.setLong(2, tableId);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }
}
