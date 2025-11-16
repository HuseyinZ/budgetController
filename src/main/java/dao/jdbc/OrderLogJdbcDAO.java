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
    private final Object schemaLock = new Object();
    private volatile boolean tableMissing;

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
        if (tableMissing) {
            return;
        }
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
            if (handleMissingTable(ex)) {
                return;
            }
            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
    }

    @Override
    public List<OrderLogEntry> findRecentByOrder(Long orderId, int limit) {
        if (tableMissing) {
            return List.of();
        }
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
                        LocalDateTime timestamp = ts == null ? LocalDateTime.now() : ts.toLocalDateTime();
                        out.add(OrderLogEntry.fromRaw(timestamp, msg));
                    }
                }
            }
        } catch (SQLException ex) {
            if (handleMissingTable(ex)) {
                return List.of();
            }

            throw new RuntimeException(ex);
        } finally {
            close(connection);
        }
        return out;
    }

    private boolean handleMissingTable(SQLException ex) {
        if (isMissingTable(ex)) {
            if (!tableMissing) {
                synchronized (schemaLock) {
                    if (!tableMissing) {
                        tableMissing = true;
                        System.err.println("Sipariş geçmişi tablosu (order_logs) bulunamadı. Günlükleme devre dışı bırakıldı. Ayrıntı: "
                                + ex.getMessage());
                    }
                }
            }
            return true;
        }
        return false;
    }

    private boolean isMissingTable(SQLException ex) {
        SQLException current = ex;
        while (current != null) {
            String state = current.getSQLState();
            if ("42S02".equals(state) || messageRefersMissingTable(current.getMessage())) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    private boolean messageRefersMissingTable(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return (lower.contains("doesn't exist") || lower.contains("does not exist")) && lower.contains("order_logs");
    }
}
