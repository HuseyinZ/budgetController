package dao.jdbc;

import DataConnection.Db;
import dao.OrderLogDAO;
import state.OrderLogEntry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class OrderLogJdbcDAO implements OrderLogDAO {

    private final DataSource dataSource;
    private final Connection externalConnection;

    public OrderLogJdbcDAO() {
        this(Db.getDataSource(), null);
    }

    public OrderLogJdbcDAO(DataSource dataSource) {
        this(dataSource, null);
    }

    public OrderLogJdbcDAO(Connection connection) {
        this(Db.getDataSource(), connection);
    }

    private OrderLogJdbcDAO(DataSource dataSource, Connection externalConnection) {
        this.dataSource = dataSource;
        this.externalConnection = externalConnection;
    }

    private Connection acquireConnection() throws SQLException {
        if (externalConnection != null) {
            return externalConnection;
        }
        if (dataSource == null) {
            throw new IllegalStateException("No DataSource configured for OrderLogJdbcDAO");
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

    @Override
    public void append(Long orderId, String message) {
        final String sql = "INSERT INTO order_logs (order_id, event_time, message) VALUES (?,?,?)";
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, orderId);
                ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
                if (message == null) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, message);
                }
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public List<OrderLogEntry> findRecentByOrder(Long orderId, int limit) {
        final String sql = "SELECT event_time, message FROM order_logs WHERE order_id=? ORDER BY event_time DESC, id DESC LIMIT ?";
        List<OrderLogEntry> out = new ArrayList<>();
        Connection connection = null;
        try {
            connection = acquireConnection();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, orderId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp ts = rs.getTimestamp("event_time");
                        String msg = rs.getString("message");
                        out.add(new OrderLogEntry(ts == null ? LocalDateTime.now() : ts.toLocalDateTime(), msg));
                    }
                }
            }
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
        return out;
    }
}
