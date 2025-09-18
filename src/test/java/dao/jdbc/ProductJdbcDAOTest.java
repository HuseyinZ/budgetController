package dao.jdbc;

import model.Product;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class ProductJdbcDAOTest {

    @Test
    void gracefullyHandlesMissingStockColumn() {
        MissingStockDataSource dataSource = new MissingStockDataSource();
        ProductJdbcDAO dao = new ProductJdbcDAO(dataSource);

        Product toCreate = new Product();
        toCreate.setName("Legacy Product");
        toCreate.setUnitPrice(new BigDecimal("10.00"));
        toCreate.setStock(5);
        toCreate.setCategoryId(3L);

        Long generatedId = dao.create(toCreate);
        assertEquals(99L, generatedId);

        Product toUpdate = new Product();
        toUpdate.setId(generatedId);
        toUpdate.setName("Legacy Product Updated");
        toUpdate.setUnitPrice(new BigDecimal("11.00"));
        toUpdate.setStock(7);
        toUpdate.setCategoryId(4L);

        dao.update(toUpdate);

        dao.updateStock(generatedId, 2);

        Optional<Product> loaded = dao.findById(generatedId);
        assertTrue(loaded.isPresent());
        assertNull(loaded.get().getStock());

        assertEquals(2, dataSource.insertAttempts());
        assertEquals(1, dataSource.updateCalls());
        assertEquals(0, dataSource.updateStockCalls());
    }

    private static final class MissingStockDataSource implements DataSource {
        private final AtomicInteger insertAttempts = new AtomicInteger();
        private final AtomicInteger updateCalls = new AtomicInteger();
        private final AtomicInteger updateStockCalls = new AtomicInteger();

        @Override
        public Connection getConnection() {
            return createConnectionProxy();
        }

        @Override
        public Connection getConnection(String username, String password) {
            return createConnectionProxy();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
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

        private Connection createConnectionProxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "MissingStockConnection";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return args != null && args.length > 0 && proxy == args[0];
                }
                if ("prepareStatement".equals(name)) {
                    String sql = (String) args[0];
                    if (sql.startsWith("INSERT INTO products")) {
                        int attempt = insertAttempts.getAndIncrement();
                        if (attempt == 0) {
                            assertTrue(sql.contains("stock"), "İlk ekleme denemesi stok sütununu içermelidir");
                            return createInsertPreparedStatement(true);
                        }
                        assertFalse(sql.contains("stock"), "Yedek ekleme sorgusu stok sütununu içermemelidir");
                        return createInsertPreparedStatement(false);
                    }
                    if (sql.startsWith("UPDATE products SET name")) {
                        updateCalls.incrementAndGet();
                        assertFalse(sql.contains("stock"), "Güncelleme sorgusu stok sütununu içermemelidir");
                        return createUpdatePreparedStatement();
                    }
                    if (sql.startsWith("UPDATE products SET stock")) {
                        updateStockCalls.incrementAndGet();
                        fail("Stok kolonu eksik olduğunda updateStock sorgusu çalıştırılmamalı");
                    }
                    if (sql.startsWith("SELECT")) {
                        return createSelectPreparedStatement();
                    }
                    fail("Beklenmeyen SQL: " + sql);
                }
                if ("close".equals(name)) {
                    return null;
                }
                if ("isClosed".equals(name)) {
                    return false;
                }
                if ("unwrap".equals(name)) {
                    return null;
                }
                if ("isWrapperFor".equals(name)) {
                    return false;
                }
                throw new UnsupportedOperationException("Connection method not supported: " + name);
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, handler);
        }

        private PreparedStatement createInsertPreparedStatement(boolean fail) {
            AtomicBoolean stockParameterSet = new AtomicBoolean(false);
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class[]{PreparedStatement.class}, (proxy, method, args) -> {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "MissingStockInsert";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return args != null && args.length > 0 && proxy == args[0];
                }
                switch (name) {
                    case "setString":
                    case "setBigDecimal":
                    case "setLong":
                        return null;
                    case "setNull": {
                        int sqlType = (int) args[1];
                        if (sqlType == Types.INTEGER) {
                            stockParameterSet.set(true);
                            if (!fail) {
                                fail("Stok sütunu olmadan setNull(Types.INTEGER) çağrılmamalı");
                            }
                        }
                        return null;
                    }
                    case "setInt":
                        stockParameterSet.set(true);
                        if (!fail) {
                            fail("Stok sütunu olmadan setInt çağrılmamalı");
                        }
                        return null;
                    case "executeUpdate":
                        if (fail) {
                            assertTrue(stockParameterSet.get(), "Hata öncesi stok parametresi ayarlanmalı");
                            throw missingStockException();
                        }
                        assertFalse(stockParameterSet.get(), "Yedek sorgu stok parametresi ayarlamamalı");
                        return 1;
                    case "getGeneratedKeys":
                        if (fail) {
                            throw new UnsupportedOperationException("Başarısız sorguda anahtar beklenmemeli");
                        }
                        return createGeneratedKeysResultSet();
                    case "close":
                        return null;
                    case "isClosed":
                        return false;
                    case "unwrap":
                        return null;
                    case "isWrapperFor":
                        return false;
                    default:
                        throw new UnsupportedOperationException("PreparedStatement method not supported: " + name);
                }
            });
        }

        private PreparedStatement createUpdatePreparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class[]{PreparedStatement.class}, (proxy, method, args) -> {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "MissingStockUpdate";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return args != null && args.length > 0 && proxy == args[0];
                }
                switch (name) {
                    case "setString":
                    case "setBigDecimal":
                    case "setNull":
                    case "setLong":
                        return null;
                    case "setInt":
                        fail("Stok sütunu olmadan güncellemede setInt çağrılmamalı");
                        return null;
                    case "executeUpdate":
                        return 1;
                    case "close":
                        return null;
                    case "isClosed":
                        return false;
                    case "unwrap":
                        return null;
                    case "isWrapperFor":
                        return false;
                    default:
                        throw new UnsupportedOperationException("PreparedStatement method not supported: " + name);
                }
            });
        }

        private PreparedStatement createSelectPreparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class[]{PreparedStatement.class}, (proxy, method, args) -> {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "MissingStockSelect";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return args != null && args.length > 0 && proxy == args[0];
                }
                switch (name) {
                    case "setLong":
                        return null;
                    case "executeQuery":
                        return createSelectResultSet();
                    case "close":
                        return null;
                    case "isClosed":
                        return false;
                    case "unwrap":
                        return null;
                    case "isWrapperFor":
                        return false;
                    default:
                        throw new UnsupportedOperationException("PreparedStatement method not supported: " + name);
                }
            });
        }

        private ResultSet createGeneratedKeysResultSet() {
            AtomicBoolean first = new AtomicBoolean(true);
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class[]{ResultSet.class}, (proxy, method, args) -> {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "MissingStockGeneratedKeys";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return args != null && args.length > 0 && proxy == args[0];
                }
                switch (name) {
                    case "next":
                        if (first.getAndSet(false)) {
                            return true;
                        }
                        return false;
                    case "getLong":
                        if (args[0] instanceof Integer && ((Integer) args[0]) == 1) {
                            return 99L;
                        }
                        throw new UnsupportedOperationException("Beklenmeyen anahtar sütunu");
                    case "close":
                        return null;
                    case "isClosed":
                        return false;
                    case "wasNull":
                        return false;
                    case "unwrap":
                        return null;
                    case "isWrapperFor":
                        return false;
                    default:
                        throw new UnsupportedOperationException("ResultSet method not supported: " + name);
                }
            });
        }

        private ResultSet createSelectResultSet() {
            AtomicInteger cursor = new AtomicInteger();
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class[]{ResultSet.class}, (proxy, method, args) -> {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "MissingStockResultSet";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return args != null && args.length > 0 && proxy == args[0];
                }
                switch (name) {
                    case "next":
                        return cursor.getAndIncrement() == 0;
                    case "getLong":
                        String column = (String) args[0];
                        if ("id".equals(column)) {
                            return 99L;
                        }
                        if ("category_id".equals(column)) {
                            return 4L;
                        }
                        throw new UnsupportedOperationException("Beklenmeyen uzun sütun: " + column);
                    case "getString":
                        if ("name".equals(args[0])) {
                            return "Legacy Product Updated";
                        }
                        throw new UnsupportedOperationException("Beklenmeyen string sütun: " + args[0]);
                    case "getBigDecimal":
                        String priceColumn = (String) args[0];
                        if ("unit_price".equals(priceColumn) || "price".equals(priceColumn)) {
                            return new BigDecimal("11.00");
                        }
                        throw new UnsupportedOperationException("Beklenmeyen fiyat sütunu: " + priceColumn);
                    case "getTimestamp":
                        return null;
                    case "getInt":
                        if ("stock".equals(args[0])) {
                            throw missingStockException();
                        }
                        throw new UnsupportedOperationException("Beklenmeyen int sütunu: " + args[0]);
                    case "wasNull":
                        return false;
                    case "close":
                        return null;
                    case "isClosed":
                        return false;
                    case "unwrap":
                        return null;
                    case "isWrapperFor":
                        return false;
                    default:
                        throw new UnsupportedOperationException("ResultSet method not supported: " + name);
                }
            });
        }

        private SQLException missingStockException() {
            return new SQLException("Unknown column 'stock'", "42S22");
        }

        int insertAttempts() {
            return insertAttempts.get();
        }

        int updateCalls() {
            return updateCalls.get();
        }

        int updateStockCalls() {
            return updateStockCalls.get();
        }
    }
}
