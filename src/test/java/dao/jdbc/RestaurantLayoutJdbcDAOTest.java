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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Masa düzeni okuyucusu yalnız SELECT çalıştırmalı: açılış yolu hiçbir koşulda
 * alan/masa oluşturmaz. JDK-Proxy JDBC stub deseni.
 */
class RestaurantLayoutJdbcDAOTest {

    @Test
    void readsAreasWithOnlySelectAndActiveFilter() {
        List<String> sqls = new CopyOnWriteArrayList<>();
        new RestaurantLayoutJdbcDAO(stubDataSource(sqls)).findActiveAreasOrdered();

        assertEquals(1, sqls.size(), sqls.toString());
        String sql = sqls.get(0);
        assertTrue(sql.toUpperCase(Locale.ROOT).startsWith("SELECT"), sql);
        assertTrue(sql.contains("FROM restaurant_areas"), sql);
        assertTrue(sql.contains("is_active = 1"), "yalnız aktif alanlar okunmalı");
        assertTrue(sql.contains("ORDER BY display_order, id"), "sıra deterministik olmalı");
    }

    @Test
    void readsTablesWithOnlySelectActiveFilterAndOrdering() {
        List<String> sqls = new CopyOnWriteArrayList<>();
        new RestaurantLayoutJdbcDAO(stubDataSource(sqls)).findActiveTablesOrdered();

        assertEquals(1, sqls.size(), sqls.toString());
        String sql = sqls.get(0);
        assertTrue(sql.toUpperCase(Locale.ROOT).startsWith("SELECT"), sql);
        assertTrue(sql.contains("FROM restaurant_table_layout"), sql);
        assertTrue(sql.contains("is_active = 1"), "yalnız aktif masalar okunmalı");
        assertTrue(sql.contains("ORDER BY display_order, table_no"), "sıra deterministik olmalı");
        // İleride kullanılacak yerleşim alanları modele taşınıyor
        for (String column : List.of("pos_x", "pos_y", "width", "height", "shape", "rotation_deg")) {
            assertTrue(sql.contains(column), "kolon eksik: " + column);
        }
    }

    @Test
    void noWriteStatementIsEverIssued() {
        List<String> sqls = new CopyOnWriteArrayList<>();
        RestaurantLayoutJdbcDAO dao = new RestaurantLayoutJdbcDAO(stubDataSource(sqls));
        dao.findActiveAreasOrdered();
        dao.findActiveTablesOrdered();

        for (String sql : sqls) {
            String upper = sql.trim().toUpperCase(Locale.ROOT);
            assertFalse(upper.startsWith("INSERT"), sql);
            assertFalse(upper.startsWith("UPDATE"), sql);
            assertFalse(upper.startsWith("DELETE"), sql);
            assertFalse(upper.startsWith("CREATE"), sql);
            assertFalse(upper.startsWith("ALTER"), sql);
        }
        assertEquals(2, sqls.size());
    }

    // ------------------------------------------------------------------

    private static DataSource stubDataSource(List<String> sqls) {
        return new StubDataSource(() -> connectionStub(sqls));
    }

    private static Connection connectionStub(List<String> sqls) {
        AtomicBoolean closed = new AtomicBoolean();
        return proxy(Connection.class, (p, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement" -> {
                    sqls.add((String) args[0]);
                    return preparedStatementStub();
                }
                case "createStatement" -> throw new AssertionError("createStatement kullanılmamalı");
                case "close" -> { closed.set(true); return null; }
                case "isClosed" -> { return closed.get(); }
                default -> { return defaultValue(method.getReturnType()); }
            }
        });
    }

    private static PreparedStatement preparedStatementStub() {
        return proxy(PreparedStatement.class, (p, method, args) -> switch (method.getName()) {
            case "executeQuery" -> emptyResultSet();
            case "execute", "executeUpdate" -> throw new AssertionError("yazma çağrısı yapılmamalı");
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ResultSet emptyResultSet() {
        return proxy(ResultSet.class, (p, method, args) -> switch (method.getName()) {
            case "next" -> false;
            default -> defaultValue(method.getReturnType());
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
