package dao.jdbc;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 0G — {@link OrderItemsJdbcDAO#isNoteColumnConfirmedAvailable()} focused testleri.
 *
 * <p>Mevcut JDK Proxy JDBC stub desenini (bkz. {@code OrderJdbcDAOTest}) izler;
 * gerçek DB gerektirmez.
 */
class OrderItemsJdbcDAOCapabilityTest {

    private static final String PROBE_SQL = "SELECT note FROM order_items WHERE 1 = 0";

    @Test
    void successfulProbeReturnsTrueAndMemoizes() {
        AtomicInteger probeCount = new AtomicInteger();
        StubDataSource ds = new StubDataSource(() -> createConnectionStub(probeCount, () -> null));
        OrderItemsJdbcDAO dao = new OrderItemsJdbcDAO(ds);

        assertTrue(dao.isNoteColumnConfirmedAvailable(), "successful probe must confirm capability");
        assertTrue(dao.isNoteColumnConfirmedAvailable(), "memoized result must stay true");
        assertEquals(1, probeCount.get(), "confirmed success must not re-probe");
    }

    @Test
    void unknownColumnVendorCodeReturnsFalseAndMemoizes() {
        AtomicInteger probeCount = new AtomicInteger();
        // Vendor code yolu: 1054, SQLState kasıtlı olarak 42S22 DEĞİL
        StubDataSource ds = new StubDataSource(
                () -> createConnectionStub(probeCount, () -> new SQLException("x", "HY000", 1054)));
        OrderItemsJdbcDAO dao = new OrderItemsJdbcDAO(ds);

        assertFalse(dao.isNoteColumnConfirmedAvailable(), "1054 must mean unsupported");
        assertFalse(dao.isNoteColumnConfirmedAvailable(), "memoized result must stay false");
        assertEquals(1, probeCount.get(), "confirmed unsupported must not re-probe");
    }

    @Test
    void unknownColumnSqlStateReturnsFalseAndMemoizes() {
        AtomicInteger probeCount = new AtomicInteger();
        // SQLState yolu: 42S22, vendor code kasıtlı olarak 1054 DEĞİL
        StubDataSource ds = new StubDataSource(
                () -> createConnectionStub(probeCount, () -> new SQLException("x", "42S22", 0)));
        OrderItemsJdbcDAO dao = new OrderItemsJdbcDAO(ds);

        assertFalse(dao.isNoteColumnConfirmedAvailable(), "42S22 must mean unsupported");
        assertFalse(dao.isNoteColumnConfirmedAvailable(), "memoized result must stay false");
        assertEquals(1, probeCount.get(), "confirmed unsupported must not re-probe");
    }

    @Test
    void genericFailureReturnsFalseButIsNotMemoized() {
        AtomicInteger probeCount = new AtomicInteger();
        AtomicBoolean failing = new AtomicBoolean(true);
        StubDataSource ds = new StubDataSource(() -> createConnectionStub(probeCount,
                () -> failing.get() ? new SQLException("conn lost", "08S01", 0) : null));
        OrderItemsJdbcDAO dao = new OrderItemsJdbcDAO(ds);

        assertFalse(dao.isNoteColumnConfirmedAvailable(), "unknown capability must disable guard");
        assertFalse(dao.isNoteColumnConfirmedAvailable(), "still unknown while failing");
        assertEquals(2, probeCount.get(), "generic failure must NOT be memoized — re-probe expected");

        failing.set(false);
        assertTrue(dao.isNoteColumnConfirmedAvailable(), "capability must be confirmable after recovery");
        assertEquals(3, probeCount.get(), "recovery probe expected");
        assertTrue(dao.isNoteColumnConfirmedAvailable(), "recovered result must memoize");
        assertEquals(3, probeCount.get(), "no further probe after confirmation");
    }

    // ------------------------------------------------------------------
    //   JDK Proxy JDBC stub altyapısı (OrderJdbcDAOTest deseni)
    // ------------------------------------------------------------------

    /**
     * @param probeCount   executeQuery çağrısı başına artar
     * @param failureMaker null dönerse probe başarılı; SQLException dönerse fırlatılır
     */
    private static Connection createConnectionStub(AtomicInteger probeCount,
                                                   Supplier<SQLException> failureMaker) {
        AtomicBoolean closed = new AtomicBoolean();
        return proxy(Connection.class, (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "prepareStatement" -> {
                    String sql = (String) args[0];
                    if (PROBE_SQL.equals(sql)) {
                        return createProbePreparedStatement(probeCount, failureMaker);
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
                case "isValid" -> {
                    return true;
                }
                default -> {
                    return defaultValueFor(method.getReturnType());
                }
            }
        });
    }

    private static PreparedStatement createProbePreparedStatement(AtomicInteger probeCount,
                                                                  Supplier<SQLException> failureMaker) {
        AtomicBoolean closed = new AtomicBoolean();
        return proxy(PreparedStatement.class, (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "executeQuery" -> {
                    probeCount.incrementAndGet();
                    SQLException failure = failureMaker.get();
                    if (failure != null) {
                        throw failure;
                    }
                    return createEmptyResultSet();
                }
                case "close" -> {
                    closed.set(true);
                    return null;
                }
                case "isClosed" -> {
                    return closed.get();
                }
                default -> {
                    return defaultValueFor(method.getReturnType());
                }
            }
        });
    }

    private static ResultSet createEmptyResultSet() {
        AtomicBoolean closed = new AtomicBoolean();
        return proxy(ResultSet.class, (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
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
                default -> {
                    return defaultValueFor(method.getReturnType());
                }
            }
        });
    }

    private static Object defaultValueFor(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
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
}
