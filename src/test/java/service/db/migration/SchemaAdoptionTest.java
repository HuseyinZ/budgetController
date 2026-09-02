package service.db.migration;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import service.db.migration.BaselineExpectations.ColumnSpec;
import service.db.migration.BaselineExpectations.Family;
import service.db.migration.BaselineExpectations.FkSpec;
import service.db.migration.BaselineExpectations.TableSpec;
import service.db.migration.SchemaInspector.Column;
import service.db.migration.SchemaInspector.ForeignKey;
import service.db.migration.SchemaInspector.Index;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adoption doğrulaması — bellek içi sahte inspector; gerçek DB yapısı gerekmez. */
class SchemaAdoptionTest {

    // ------------------------------------------------------------------
    //  Beklentilerden birebir türetilen "uyumlu" sahte şema
    // ------------------------------------------------------------------
    private static FakeInspector conformingSchema() {
        FakeInspector f = new FakeInspector();
        for (TableSpec t : BaselineExpectations.baseline().values()) {
            Map<String, Column> cols = new LinkedHashMap<>();
            for (ColumnSpec c : t.columns().values()) {
                cols.put(c.name(), new Column(c.name(), dataTypeFor(c), columnTypeFor(c),
                        !c.notNull(), c.autoIncrement(), c.generated()));
            }
            List<Index> idx = new ArrayList<>();
            idx.add(new Index("PRIMARY", true, List.of("id")));
            for (List<String> uq : t.uniqueColumnSets()) idx.add(new Index("uq_" + String.join("_", uq), true, uq));
            List<ForeignKey> fks = new ArrayList<>();
            for (FkSpec fk : t.foreignKeys()) {
                fks.add(new ForeignKey(fk.column(), fk.referencedTable(),
                        fk.deleteRule() == null ? "NO ACTION" : fk.deleteRule()));
            }
            List<String> checks = new ArrayList<>();
            for (String c : t.requiredChecks()) checks.add("(" + c + ")");
            f.put(t.name(), cols, idx, fks, checks);
        }
        return f;
    }

    private static String dataTypeFor(ColumnSpec c) {
        return switch (c.family()) {
            case INTEGER -> "int";
            case DECIMAL -> "decimal";
            case TEXT -> "varchar";
            case TEMPORAL -> "datetime";
            case DATE -> "date";
            case ENUM -> "enum";
        };
    }

    private static String columnTypeFor(ColumnSpec c) {
        if (c.family() != Family.ENUM) return dataTypeFor(c);
        return "enum(" + String.join(",", c.enumValues().stream().map(v -> "'" + v + "'").toList()) + ")";
    }

    private static List<String> validate(FakeInspector f) {
        return SchemaAdoption.validate(f, BaselineExpectations.baseline());
    }

    // ------------------------------------------------------------------

    @Test
    void conformingSchemaPassesValidation() {
        List<String> problems = validate(conformingSchema());
        assertTrue(problems.isEmpty(), "sorun bulunmamalı: " + problems);
    }

    @Test
    void cosmeticDifferencesAreTolerated() {
        FakeInspector f = conformingSchema();
        f.replaceType("orders", "id", "bigint");                 // INT/BIGINT
        f.replaceType("order_items", "updated_at", "timestamp"); // DATETIME/TIMESTAMP
        f.replaceType("expenses", "note", "text");               // VARCHAR/TEXT
        f.setNullable("orders", "waiter_id", false);             // üretimde daha sıkı NOT NULL → sorun değil
        f.setDeleteRule("order_items", "product_id", "NO ACTION"); // RESTRICT ≡ NO ACTION
        f.setDeleteRule("users", "role_id", "SET NULL");          // kural beklentisi yok → serbest
        f.replaceCheck("order_items", "(`quantity` > 0)");        // backtick/boşluk normalize
        assertTrue(validate(f).isEmpty(), validate(f).toString());
    }

    @Test
    void structuralProblemsAreAllReported() {
        FakeInspector f = conformingSchema();
        f.removeTable("reservations");
        f.removeColumn("order_items", "note");
        f.replaceType("payments", "amount", "varchar");
        f.replaceColumnType("orders", "status", "enum('PENDING','COMPLETED')"); // 3 değer eksik
        f.removeUnique("users", List.of("username"));
        f.removeFk("order_items", "product_id");

        List<String> p = validate(f);
        assertTrue(p.stream().anyMatch(x -> x.startsWith("reservations: tablo yok")), p.toString());
        assertTrue(p.stream().anyMatch(x -> x.contains("order_items.note: kolon yok")));
        assertTrue(p.stream().anyMatch(x -> x.contains("payments.amount: tip ailesi")));
        assertEquals(3, p.stream().filter(x -> x.contains("orders.status: ENUM değeri eksik")).count());
        assertTrue(p.stream().anyMatch(x -> x.contains("users: UNIQUE [username] yok")));
        assertTrue(p.stream().anyMatch(x -> x.contains("order_items.product_id: FK → products yok")));
    }

    @Test
    void behavioralConstraintsAreValidated() {
        FakeInspector f = conformingSchema();
        f.setNullable("order_items", "quantity", true);           // kritik NOT NULL kaybı
        f.setAutoIncrement("orders", "id", false);                // AUTO_INCREMENT kaybı
        f.setGenerated("order_items", "line_total", false);       // hesaplanan tutar düz kolon olmuş
        f.setDeleteRule("order_items", "order_id", "RESTRICT");   // CASCADE bekleniyor
        f.clearChecks("order_items");                             // quantity > 0 CHECK yok
        f.removePrimary("payments");

        List<String> p = validate(f);
        assertTrue(p.contains("order_items.quantity: NOT NULL bekleniyor"), p.toString());
        assertTrue(p.contains("orders.id: AUTO_INCREMENT bekleniyor"));
        assertTrue(p.contains("order_items.line_total: GENERATED kolon bekleniyor (hesaplanan tutar)"));
        assertTrue(p.stream().anyMatch(x -> x.startsWith("order_items.order_id: ON DELETE CASCADE bekleniyor")));
        assertTrue(p.contains("order_items: CHECK (quantity > 0) yok"));
        assertTrue(p.contains("payments: PRIMARY KEY (id) yok"));
    }

    @Test
    void adoptWritesNothingOnMismatchAndMarksOnlyBaselineOnSuccess() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:adopt_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            List<Migration> ms = List.of(
                    Migration.fromFile("V001__baseline.sql", "-- adoption'da çalıştırılmaz"),
                    Migration.fromFile("V002__seed_roles.sql", "-- bekleyen kalmalı"),
                    Migration.fromFile("V003__seed_printers.sql", "-- bekleyen kalmalı"));
            SchemaMigrator mig = new SchemaMigrator(c, ms);

            // Adoption öncesi: status salt okuma, tablo YARATMAZ
            SchemaMigrator.Status before = mig.status();
            assertFalse(before.metadataInitialized());
            assertEquals(3, before.pending().size());
            assertFalse(mig.versionTableExists(), "status() schema_version yaratmamalı");

            // 1) uyumsuz → hata + hiç kayıt yok
            FakeInspector bad = conformingSchema();
            bad.removeTable("print_jobs");
            SchemaMigrator.MigrationException ex = assertThrows(SchemaMigrator.MigrationException.class,
                    () -> SchemaAdoption.adopt(c, bad, ms, BaselineExpectations.baseline()));
            assertTrue(ex.getMessage().contains("hiçbir kayıt yazılmadı"));
            assertFalse(mig.versionTableExists(), "başarısız adoption iz bırakmamalı");

            // 2) uyumlu → YALNIZ V001 işaretlenir; V002/V003 bekleyen
            assertEquals(1, SchemaAdoption.adopt(c, conformingSchema(), ms, BaselineExpectations.baseline()));
            SchemaMigrator.Status after = mig.status();
            assertTrue(after.metadataInitialized());
            assertEquals(List.of(1), after.applied().stream().map(SchemaMigrator.Applied::version).toList());
            assertEquals(List.of(2, 3), after.pending().stream().map(Migration::version).toList(),
                    "seed'ler adoption ile gizlenmemeli — --apply ile uygulanır");
            assertFalse(after.isUpToDate());
            assertEquals(ms.get(0).checksum(), mig.readApplied().get(1).checksum());

            // 3) zaten versiyonlanmış DB'de tekrar adoption reddedilir
            assertThrows(SchemaMigrator.MigrationException.class,
                    () -> SchemaAdoption.adopt(c, conformingSchema(), ms, BaselineExpectations.baseline()));

            // 4) --apply: V002/V003 (yorum-only, ifadesiz) uygulanmış sayılır → güncel
            SchemaMigrator.ApplyResult r = mig.apply();
            assertEquals(List.of(2, 3), r.appliedNow());
            assertTrue(mig.status().isUpToDate());
            try (Statement st = c.createStatement()) { st.execute("DROP ALL OBJECTS"); }
        }
    }

    @Test
    void adoptRequiresBaselineVersion() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:adoptnb_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection()) {
            List<Migration> noBaseline = List.of(Migration.fromFile("V002__x.sql", "-- x"));
            assertThrows(SchemaMigrator.MigrationException.class,
                    () -> SchemaAdoption.adopt(c, conformingSchema(), noBaseline, BaselineExpectations.baseline()));
        }
    }

    // ---------------- Spec ↔ V001 senkron kontrolü ----------------

    @Test
    void expectationsMatchBaselineSqlTablesAndColumns() {
        List<Migration> found = MigrationDiscovery.discover();
        String v001 = found.get(0).sql();
        Map<String, List<String>> sqlTables = parseTablesAndColumns(v001);
        Map<String, TableSpec> spec = BaselineExpectations.baseline();

        assertEquals(sqlTables.keySet(), spec.keySet(), "V001 tabloları ile beklenti tabloları birebir aynı olmalı");
        for (Map.Entry<String, List<String>> e : sqlTables.entrySet()) {
            List<String> specCols = new ArrayList<>(spec.get(e.getKey()).columns().keySet());
            assertEquals(e.getValue(), specCols, "kolon listesi/sırası farklı: " + e.getKey());
        }
        // V001'deki GENERATED kolonlar spec'te de generated olmalı
        long generatedInSql = v001.lines().filter(l -> l.contains("GENERATED ALWAYS")).count();
        long generatedInSpec = spec.values().stream()
                .flatMap(t -> t.columns().values().stream()).filter(ColumnSpec::generated).count();
        assertEquals(generatedInSql, generatedInSpec, "GENERATED kolon sayısı senkron olmalı");
    }

    private static Map<String, List<String>> parseTablesAndColumns(String sql) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Pattern table = Pattern.compile("CREATE TABLE IF NOT EXISTS (\\w+) \\((.*?)\\) ENGINE=", Pattern.DOTALL);
        Pattern column = Pattern.compile("^\\s*([a-z_]+)\\s+(?:INT|BIGINT|SMALLINT|TINYINT|VARCHAR|CHAR|DECIMAL|DATETIME|TIMESTAMP|DATE|ENUM|LONGTEXT)\\b",
                Pattern.MULTILINE);
        Matcher m = table.matcher(sql);
        while (m.find()) {
            List<String> cols = new ArrayList<>();
            Matcher cm = column.matcher(m.group(2));
            while (cm.find()) cols.add(cm.group(1));
            out.put(m.group(1), cols);
        }
        return out;
    }

    // ---------------- sahte inspector ----------------

    static final class FakeInspector implements SchemaInspector {
        private final Map<String, Map<String, Column>> cols = new HashMap<>();
        private final Map<String, List<Index>> idx = new HashMap<>();
        private final Map<String, List<ForeignKey>> fks = new HashMap<>();
        private final Map<String, List<String>> checks = new HashMap<>();

        void put(String t, Map<String, Column> c, List<Index> i, List<ForeignKey> f, List<String> ch) {
            cols.put(t, c); idx.put(t, i); fks.put(t, f); checks.put(t, ch);
        }
        void removeTable(String t) { cols.remove(t); idx.remove(t); fks.remove(t); checks.remove(t); }
        void removeColumn(String t, String c) { cols.get(t).remove(c); }
        private void mutate(String t, String c, java.util.function.Function<Column, Column> fn) {
            cols.get(t).put(c, fn.apply(cols.get(t).get(c)));
        }
        void replaceType(String t, String c, String dataType) {
            mutate(t, c, o -> new Column(c, dataType, dataType, o.nullable(), o.autoIncrement(), o.generated()));
        }
        void replaceColumnType(String t, String c, String columnType) {
            mutate(t, c, o -> new Column(c, o.dataType(), columnType, o.nullable(), o.autoIncrement(), o.generated()));
        }
        void setNullable(String t, String c, boolean v) {
            mutate(t, c, o -> new Column(c, o.dataType(), o.columnType(), v, o.autoIncrement(), o.generated()));
        }
        void setAutoIncrement(String t, String c, boolean v) {
            mutate(t, c, o -> new Column(c, o.dataType(), o.columnType(), o.nullable(), v, o.generated()));
        }
        void setGenerated(String t, String c, boolean v) {
            mutate(t, c, o -> new Column(c, o.dataType(), o.columnType(), o.nullable(), o.autoIncrement(), v));
        }
        void removeUnique(String t, List<String> columns) { idx.get(t).removeIf(i -> i.unique() && i.columns().equals(columns)); }
        void removePrimary(String t) { idx.get(t).removeIf(i -> i.columns().equals(List.of("id"))); }
        void removeFk(String t, String column) { fks.get(t).removeIf(f -> f.column().equals(column)); }
        void setDeleteRule(String t, String column, String rule) {
            fks.get(t).replaceAll(f -> f.column().equals(column) ? new ForeignKey(f.column(), f.referencedTable(), rule) : f);
        }
        void replaceCheck(String t, String rawClause) {
            checks.put(t, new ArrayList<>(List.of(JdbcSchemaInspector.normalizeClause(rawClause))));
        }
        void clearChecks(String t) { checks.put(t, new ArrayList<>()); }

        @Override public boolean tableExists(String table) { return cols.containsKey(table); }
        @Override public Map<String, Column> columns(String table) { return cols.getOrDefault(table, Map.of()); }
        @Override public List<Index> indexes(String table) { return idx.getOrDefault(table, List.of()); }
        @Override public List<ForeignKey> foreignKeys(String table) { return fks.getOrDefault(table, List.of()); }
        @Override public List<String> checkClauses(String table) { return checks.getOrDefault(table, List.of()); }
    }
}
