package service.db;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import service.db.migration.Migration;
import service.db.migration.SchemaMigrator;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1-c açılış doğrulaması — sunucusuz H2 + "salt okuma bekçisi" proxy'si:
 * doğrulama sırasında SELECT dışı her SQL testi patlatır.
 */
class SchemaStartupCheckTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:startup_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        conn = ds.getConnection();
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = conn.createStatement()) { st.execute("DROP ALL OBJECTS"); }
        conn.close();
    }

    private static Migration m(int v, String name, String sql) {
        return Migration.fromFile("V" + v + "__" + name + ".sql", sql);
    }

    private static final List<Migration> CURRENT = List.of(
            m(1, "baseline", "CREATE TABLE t (id INT PRIMARY KEY);"),
            m(2, "seed", "INSERT INTO t VALUES (1);"));

    @Test
    void currentSchemaPasses() {
        new SchemaMigrator(conn, CURRENT).apply();
        SchemaStartupCheck.Result r = SchemaStartupCheck.verify(readOnly(conn), CURRENT);
        assertTrue(r.ok(), r.detail());
    }

    @Test
    void pendingMigrationFails() {
        new SchemaMigrator(conn, CURRENT.subList(0, 1)).apply(); // yalnız V1 uygulanmış
        SchemaStartupCheck.Result r = SchemaStartupCheck.verify(readOnly(conn), CURRENT);
        assertFalse(r.ok());
        assertTrue(r.detail().contains("pending") && r.detail().contains("V2"), r.detail());
    }

    @Test
    void checksumMismatchFails() {
        new SchemaMigrator(conn, CURRENT).apply();
        List<Migration> tampered = List.of(
                m(1, "baseline", "CREATE TABLE t (id BIGINT PRIMARY KEY);"), // içerik değişti
                CURRENT.get(1));
        SchemaStartupCheck.Result r = SchemaStartupCheck.verify(readOnly(conn), tampered);
        assertFalse(r.ok());
        assertTrue(r.detail().contains("content changed") && r.detail().contains("V1"), r.detail());
    }

    @Test
    void missingSchemaVersionFailsWithoutCreatingIt() throws Exception {
        List<String> executed = new CopyOnWriteArrayList<>();
        SchemaStartupCheck.Result r = SchemaStartupCheck.verify(readOnly(conn, executed), CURRENT);
        assertFalse(r.ok());
        assertTrue(r.detail().contains("schema_version"), r.detail());
        assertFalse(tableExists("schema_version"), "doğrulama schema_version YARATMAMALI");
        assertTrue(executed.stream().allMatch(s -> s.toLowerCase(Locale.ROOT).startsWith("select")),
                "yalnız SELECT beklenir: " + executed);
    }

    @Test
    void verificationNeverExecutesDdlOrDml() throws Exception {
        new SchemaMigrator(conn, CURRENT).apply();
        List<String> executed = new CopyOnWriteArrayList<>();
        SchemaStartupCheck.verify(readOnly(conn, executed), CURRENT);
        assertFalse(executed.isEmpty(), "en az schema_version SELECT'i beklenir");
        assertTrue(executed.stream().allMatch(s -> s.toLowerCase(Locale.ROOT).startsWith("select")),
                "DDL/DML tespit edildi: " + executed);
    }

    @Test
    void noMigrationsOnClasspathFails() {
        SchemaStartupCheck.Result r = SchemaStartupCheck.verify(readOnly(conn), List.of());
        assertFalse(r.ok());
        assertTrue(r.detail().contains("no migrations"));
    }

    @Test
    void schemaPatcherIsGone() {
        // C1-c: runtime şema mutasyonu sınıfı kaldırıldı — App onu çağıramaz.
        assertThrows(ClassNotFoundException.class, () -> Class.forName("service.db.SchemaPatcher"));
    }

    @Test
    void messageIsTheDocumentedOperatorInstruction() {
        assertEquals("Database schema is not ready. Run Migrate --status / --apply / --adopt-existing.",
                SchemaStartupCheck.MESSAGE);
    }

    // ------------------------------------------------------------------
    //  Salt okuma bekçisi: Statement/PreparedStatement üzerinden giden her SQL
    //  kaydedilir; SELECT ile başlamayan ifade AssertionError fırlatır.
    // ------------------------------------------------------------------
    private static Connection readOnly(Connection real) {
        return readOnly(real, new CopyOnWriteArrayList<>());
    }

    private static Connection readOnly(Connection real, List<String> executed) {
        return proxy(Connection.class, real, executed);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, Object target, List<String> executed) {
        InvocationHandler h = (p, method, args) -> {
            String name = method.getName();
            if (target instanceof Statement && args != null && args.length > 0
                    && args[0] instanceof String && name.startsWith("execute")) {
                guard((String) args[0], executed);
            }
            if (target instanceof Connection && "prepareStatement".equals(name)) {
                guard((String) args[0], executed);
            }
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (result instanceof PreparedStatement) return proxy(PreparedStatement.class, result, executed);
            if (result instanceof Statement) return proxy(Statement.class, result, executed);
            return result;
        };
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, h);
    }

    private static void guard(String sql, List<String> executed) {
        String s = sql.trim();
        executed.add(s);
        if (!s.toLowerCase(Locale.ROOT).startsWith("select")) {
            throw new AssertionError("Salt okuma ihlali — DDL/DML çalıştırıldı: " + s);
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) return true;
            }
        }
        return false;
    }
}
