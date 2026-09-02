package dao.jdbc;

import model.RefundLog;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1-c: RefundLogJdbcDAO artık runtime'da CREATE TABLE çalıştırmaz; tablo yoksa
 * normal SQL hatası yükselir. JDK-Proxy JDBC stub deseni (OrderJdbcDAOTest gibi).
 */
class RefundLogJdbcDAOTest {

    @Test
    void createIssuesOnlyInsertNoDdl() {
        List<String> sqls = new CopyOnWriteArrayList<>();
        RefundLogJdbcDAO dao = new RefundLogJdbcDAO(new StubDataSource(() -> connectionStub(sqls, false)));

        RefundLog log = new RefundLog();
        log.setActionType(RefundLog.ActionType.DECREASE_ITEM);
        log.setUserName("kasiyer");
        log.setTableNo(5);
        log.setQuantity(1);
        Long id = dao.create(log);

        assertEquals(42L, id);
        assertEquals(1, sqls.size(), "tek SQL beklenir: " + sqls);
        assertTrue(sqls.get(0).toUpperCase(Locale.ROOT).startsWith("INSERT INTO REFUND_LOG"));
        assertFalse(sqls.stream().anyMatch(RefundLogJdbcDAOTest::isCreateStatement),
                "runtime CREATE TABLE olmamalı");
    }

    @Test
    void queriesIssueOnlySelectNoDdl() {
        List<String> sqls = new CopyOnWriteArrayList<>();
        RefundLogJdbcDAO dao = new RefundLogJdbcDAO(new StubDataSource(() -> connectionStub(sqls, false)));

        assertTrue(dao.findAll().isEmpty());
        assertTrue(dao.findByUserId(7L).isEmpty());
        assertEquals(2, sqls.size());
        assertTrue(sqls.stream().allMatch(s -> s.toUpperCase(Locale.ROOT).startsWith("SELECT")), sqls.toString());
    }

    @Test
    void missingTableSurfacesAsNormalSqlErrorWithoutAutoCreate() {
        List<String> sqls = new CopyOnWriteArrayList<>();
        RefundLogJdbcDAO dao = new RefundLogJdbcDAO(new StubDataSource(() -> connectionStub(sqls, true)));

        RuntimeException ex = assertThrows(RuntimeException.class, dao::findAll);
        assertTrue(ex.getCause() instanceof SQLException, "SQLException sarılmış olmalı");
        assertEquals("42S02", ((SQLException) ex.getCause()).getSQLState());
        // Komut tipine bak (SELECT içindeki "created_at" gibi kolon adları CREATE sayılmaz)
        assertFalse(sqls.stream().anyMatch(RefundLogJdbcDAOTest::isCreateStatement),
                "tablo yokken CREATE denenmemeli: " + sqls);
        assertTrue(sqls.stream().allMatch(s -> s.trim().toUpperCase(Locale.ROOT).startsWith("SELECT")),
                "yalnız SELECT beklenir: " + sqls);
    }

    /** SQL ifadesi gerçekten CREATE komutuyla mı başlıyor? (^CREATE\b, case-insensitive) */
    private static boolean isCreateStatement(String sql) {
        return sql != null && sql.trim().toUpperCase(Locale.ROOT).matches("^CREATE\\b[\\s\\S]*");
    }

    // ------------------------------------------------------------------

    /** @param tableMissing true → her prepareStatement 42S02 (table not found) fırlatır */
    private static Connection connectionStub(List<String> sqls, boolean tableMissing) {
        AtomicBoolean closed = new AtomicBoolean();
        return proxy(Connection.class, (p, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement" -> {
                    String sql = (String) args[0];
                    sqls.add(sql);
                    if (tableMissing) {
                        throw new SQLException("Table 'posdb.refund_log' doesn't exist", "42S02", 1146);
                    }
                    return preparedStatementStub(sql);
                }
                case "close" -> { closed.set(true); return null; }
                case "isClosed" -> { return closed.get(); }
                case "getAutoCommit" -> { return true; }
                case "isValid" -> { return true; }
                default -> { return defaultValue(method.getReturnType()); }
            }
        });
    }

    private static PreparedStatement preparedStatementStub(String sql) {
        AtomicBoolean closed = new AtomicBoolean();
        return proxy(PreparedStatement.class, (p, method, args) -> {
            switch (method.getName()) {
                case "executeUpdate" -> { return 1; }
                case "executeQuery" -> { return resultSetStub(false); }
                case "getGeneratedKeys" -> { return resultSetStub(true); }
                case "close" -> { closed.set(true); return null; }
                case "isClosed" -> { return closed.get(); }
                default -> { return defaultValue(method.getReturnType()); }
            }
        });
    }

    private static ResultSet resultSetStub(boolean oneRowWithId42) {
        AtomicBoolean consumed = new AtomicBoolean(!oneRowWithId42);
        return proxy(ResultSet.class, (p, method, args) -> {
            switch (method.getName()) {
                case "next" -> { return !consumed.getAndSet(true); }
                case "getLong" -> { return 42L; }
                case "close" -> { return null; }
                default -> { return defaultValue(method.getReturnType()); }
            }
        });
    }

    private static Object defaultValue(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class StubDataSource implements DataSource {
        private final java.util.function.Supplier<Connection> supplier;
        private StubDataSource(java.util.function.Supplier<Connection> supplier) { this.supplier = supplier; }
        @Override public Connection getConnection() { return supplier.get(); }
        @Override public Connection getConnection(String u, String p) { return supplier.get(); }
        @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
        @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
