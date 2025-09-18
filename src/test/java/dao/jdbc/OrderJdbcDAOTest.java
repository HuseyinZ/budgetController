package dao.jdbc;

import model.OrderStatus;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class OrderJdbcDAOTest {

    @Test
    void updateStatusFallsBackForMissingEnumValues() throws Exception {
        List<String> statusWrites = new ArrayList<>();
        List<Long> idWrites = new ArrayList<>();
        StubDataSource dataSource = new StubDataSource(() -> createConnectionStub(statusWrites, idWrites));

        OrderJdbcDAO dao = new OrderJdbcDAO(dataSource);

        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuffer));
        try {
            dao.updateStatus(101L, OrderStatus.READY);
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(List.of("PENDING"), statusWrites, "READY should downgrade to PENDING");
        assertEquals(List.of(101L), idWrites, "Order id must be preserved");

        String warnings = errBuffer.toString();
        assertTrue(warnings.contains("'READY'"), "schema warning should mention READY");
        assertTrue(warnings.contains("'PENDING'"), "fallback target PENDING should be announced");
        assertTrue(warnings.contains("'CANCELLED'"), "CANCELLED should be flagged as unsupported");
        assertTrue(warnings.contains("'COMPLETED'"), "CANCELLED fallback must reference COMPLETED");

        statusWrites.clear();
        idWrites.clear();

        ByteArrayOutputStream secondBuffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(secondBuffer));
        try {
            dao.updateStatus(202L, OrderStatus.CANCELLED);
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(List.of("COMPLETED"), statusWrites, "CANCELLED should downgrade to COMPLETED");
        assertEquals(List.of(202L), idWrites, "Second update should target provided id");
        assertEquals("", secondBuffer.toString(), "Known unsupported statuses must not warn again");

        Set<OrderStatus> unsupported = readStatusSet(dao, "unsupportedStatuses");
        assertTrue(unsupported.contains(OrderStatus.IN_PROGRESS));
        assertTrue(unsupported.contains(OrderStatus.READY));
        assertTrue(unsupported.contains(OrderStatus.CANCELLED));

        Set<OrderStatus> supported = readStatusSet(dao, "supportedStatuses");
        assertEquals(Set.of(OrderStatus.PENDING, OrderStatus.COMPLETED), supported);
    }

    @SuppressWarnings("unchecked")
    private static Set<OrderStatus> readStatusSet(OrderJdbcDAO dao, String field) throws Exception {
        Field f = OrderJdbcDAO.class.getDeclaredField(field);
        f.setAccessible(true);
        return (Set<OrderStatus>) f.get(dao);
    }

    private static Connection createConnectionStub(List<String> statusWrites, List<Long> idWrites) {
        AtomicBoolean closed = new AtomicBoolean();
        return proxy(Connection.class, (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "prepareStatement" -> {
                    String sql = (String) args[0];
                    if ("SELECT status FROM orders LIMIT 1".equals(sql)) {
                        return createSelectPreparedStatement();
                    }
                    if ("UPDATE orders SET status=?, updated_at=NOW() WHERE id=?".equals(sql)) {
                        return createUpdatePreparedStatement(statusWrites, idWrites);
                    }
                    throw new UnsupportedOperationException("Unexpected SQL: " + sql);
                }
                case "close" -> {
                    closed.set(true);
                    return null;
                }
                case "isClosed" -> {
                    return closed.get();
                }
                case "getAutoCommit" -> {
                    return true;
                }
                case "setAutoCommit", "commit", "rollback", "setReadOnly", "setCatalog",
                        "setTransactionIsolation", "clearWarnings", "setHoldability", "setClientInfo",
                        "setSchema", "setNetworkTimeout", "abort" -> {
                    return null;
                }
                case "getWarnings" -> {
                    return null;
                }
                case "nativeSQL" -> {
                    return args[0];
                }
                case "getSchema", "getCatalog" -> {
                    return null;
                }
                case "isReadOnly" -> {
                    return false;
                }
                case "isValid" -> {
                    return true;
                }
                case "unwrap" -> {
                    return null;
                }
                case "isWrapperFor" -> {
                    return false;
                }
                default -> {
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    return null;
                }
            }
        });
    }

    private static PreparedStatement createSelectPreparedStatement() {
        AtomicBoolean closed = new AtomicBoolean();
        return proxy(PreparedStatement.class, (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "executeQuery" -> {
                    return createSelectResultSet();
                }
                case "close" -> {
                    closed.set(true);
                    return null;
                }
                case "isClosed" -> {
                    return closed.get();
                }
                case "unwrap" -> {
                    return null;
                }
                case "isWrapperFor" -> {
                    return false;
                }
                default -> {
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    return null;
                }
            }
        });
    }

    private static ResultSet createSelectResultSet() {
        AtomicBoolean closed = new AtomicBoolean();
        ResultSetMetaData metaData = createResultSetMetaData();
        return proxy(ResultSet.class, (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "getMetaData" -> {
                    return metaData;
                }
                case "next" -> {
                    return false;
                }
                case "close" -> {
                    closed.set(true);
                    return null;
                }
                case "isClosed" -> {
                    return closed.get();
                }
                case "wasNull" -> {
                    return false;
                }
                case "unwrap" -> {
                    return null;
                }
                case "isWrapperFor" -> {
                    return false;
                }
                default -> {
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    if (method.getReturnType() == String.class) {
                        return null;
                    }
                    return null;
                }
            }
        });
    }

    private static ResultSetMetaData createResultSetMetaData() {
        return proxy(ResultSetMetaData.class, (proxy, method, args) -> {
            String name = method.getName();
            return switch (name) {
                case "getColumnCount" -> 1;
                case "getColumnType" -> Types.VARCHAR;
                case "getColumnTypeName" -> "enum('PENDING','COMPLETED')";
                default -> {
                    if (method.getReturnType() == boolean.class) {
                        yield false;
                    }
                    if (method.getReturnType() == int.class) {
                        yield 0;
                    }
                    if (method.getReturnType() == String.class) {
                        yield "";
                    }
                    yield null;
                }
            };
        });
    }

    private static PreparedStatement createUpdatePreparedStatement(List<String> statusWrites,
                                                                   List<Long> idWrites) {
        AtomicReference<String> statusRef = new AtomicReference<>();
        AtomicReference<Integer> ordinalRef = new AtomicReference<>();
        AtomicReference<Long> idRef = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();
        return proxy(PreparedStatement.class, (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "setString" -> {
                    if ((int) args[0] == 1) {
                        statusRef.set((String) args[1]);
                    }
                    return null;
                }
                case "setInt" -> {
                    if ((int) args[0] == 1) {
                        ordinalRef.set((Integer) args[1]);
                    }
                    return null;
                }
                case "setLong" -> {
                    if ((int) args[0] == 2) {
                        idRef.set((Long) args[1]);
                    }
                    return null;
                }
                case "executeUpdate" -> {
                    if (statusRef.get() != null) {
                        statusWrites.add(statusRef.get());
                    } else if (ordinalRef.get() != null) {
                        statusWrites.add(String.valueOf(ordinalRef.get()));
                    }
                    if (idRef.get() != null) {
                        idWrites.add(idRef.get());
                    }
                    return 1;
                }
                case "close" -> {
                    closed.set(true);
                    return null;
                }
                case "isClosed" -> {
                    return closed.get();
                }
                case "clearParameters" -> {
                    statusRef.set(null);
                    ordinalRef.set(null);
                    idRef.set(null);
                    return null;
                }
                case "unwrap" -> {
                    return null;
                }
                case "isWrapperFor" -> {
                    return false;
                }
                default -> {
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    return null;
                }
            }
        });
    }

    private static final class StubDataSource implements DataSource {
        private final Supplier<Connection> supplier;

        private StubDataSource(Supplier<Connection> supplier) {
            this.supplier = supplier;
        }

        @Override
        public Connection getConnection() {
            return supplier.get();
        }

        @Override
        public Connection getConnection(String username, String password) {
            return supplier.get();
        }

        @Override
        public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // ignored
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        InvocationHandler composite = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> type.getSimpleName() + "Proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                    default -> handler.invoke(proxy, method, args);
                };
            }
            return handler.invoke(proxy, method, args);
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, composite);
    }
}
