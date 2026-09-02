package service.db.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.db.migration.BaselineExpectations.ColumnSpec;
import service.db.migration.BaselineExpectations.Family;
import service.db.migration.BaselineExpectations.FkSpec;
import service.db.migration.BaselineExpectations.TableSpec;
import service.db.migration.SchemaInspector.Column;
import service.db.migration.SchemaInspector.ForeignKey;
import service.db.migration.SchemaInspector.Index;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Mevcut (elle evrilmiş) bir üretim şemasını versiyon sistemine ALIR.
 *
 * <p>Kurallar:
 * <ul>
 *   <li>Yalnız canonical baseline (<b>V001</b>) adopt edilir. Seed'ler (V002, V003)
 *       BEKLEYEN kalır; operatör sonra {@code --apply} çalıştırır — idempotent
 *       seed'ler eksik rolleri/placeholder yazıcıları ekler, mevcut gerçek satırlara
 *       dokunmaz. Böylece adoption eksik seed'i "uygulanmış" gibi gizlemez.</li>
 *   <li>Yalnız tablo varlığına bakılmaz — kolon/tip ailesi/ENUM/NOT NULL/AUTO_INCREMENT/
 *       GENERATED/PK/UNIQUE/FK+ON DELETE/CHECK doğrulanır.</li>
 *   <li>Tek bir uyumsuzlukta bile FAIL: {@code schema_version} tablosu bile yaratılmaz.</li>
 *   <li>Şema veya veri DEĞİŞTİRİLMEZ — ne DDL ne DML; bakım işleri ayrı, explicit adımdır.</li>
 *   <li>{@code schema_version} zaten kayıt içeriyorsa adoption reddedilir.</li>
 * </ul>
 */
public final class SchemaAdoption {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaAdoption.class);

    /** Adopt edilen tek migration: canonical baseline. */
    public static final int BASELINE_VERSION = 1;

    private SchemaAdoption() {}

    /** Yapısal doğrulama — boş liste = uyumlu. Hiçbir şey yazmaz. */
    public static List<String> validate(SchemaInspector inspector, Map<String, TableSpec> expectations) {
        Objects.requireNonNull(inspector, "inspector");
        List<String> problems = new ArrayList<>();
        for (TableSpec spec : expectations.values()) {
            String table = spec.name();
            if (!inspector.tableExists(table)) {
                problems.add(table + ": tablo yok");
                continue;
            }
            validateColumns(inspector, spec, problems);
            validateIndexes(inspector, spec, problems);
            validateForeignKeys(inspector, spec, problems);
            validateChecks(inspector, spec, problems);
        }
        return Collections.unmodifiableList(problems);
    }

    private static void validateColumns(SchemaInspector inspector, TableSpec spec, List<String> problems) {
        String table = spec.name();
        Map<String, Column> actual = inspector.columns(table);
        for (ColumnSpec col : spec.columns().values()) {
            String ref = table + "." + col.name();
            Column a = actual.get(col.name());
            if (a == null) {
                problems.add(ref + ": kolon yok");
                continue;
            }
            Family family = BaselineExpectations.familyOf(a.dataType());
            if (family != col.family()) {
                problems.add(ref + ": tip ailesi " + col.family() + " bekleniyor, bulunan " + a.dataType());
                continue;
            }
            if (col.family() == Family.ENUM) {
                String ct = a.columnType() == null ? "" : a.columnType().toLowerCase(Locale.ROOT);
                for (String v : col.enumValues()) {
                    if (!ct.contains("'" + v.toLowerCase(Locale.ROOT) + "'")) {
                        problems.add(ref + ": ENUM değeri eksik: " + v);
                    }
                }
            }
            if (col.notNull() && a.nullable()) {
                problems.add(ref + ": NOT NULL bekleniyor");
            }
            if (col.autoIncrement() && !a.autoIncrement()) {
                problems.add(ref + ": AUTO_INCREMENT bekleniyor");
            }
            if (col.generated() && !a.generated()) {
                problems.add(ref + ": GENERATED kolon bekleniyor (hesaplanan tutar)");
            }
        }
    }

    private static void validateIndexes(SchemaInspector inspector, TableSpec spec, List<String> problems) {
        List<Index> indexes = inspector.indexes(spec.name());
        if (!hasUnique(indexes, List.of("id"))) {
            problems.add(spec.name() + ": PRIMARY KEY (id) yok");
        }
        for (List<String> uq : spec.uniqueColumnSets()) {
            if (!hasUnique(indexes, uq)) {
                problems.add(spec.name() + ": UNIQUE " + uq + " yok");
            }
        }
    }

    private static void validateForeignKeys(SchemaInspector inspector, TableSpec spec, List<String> problems) {
        List<ForeignKey> fks = inspector.foreignKeys(spec.name());
        for (FkSpec want : spec.foreignKeys()) {
            ForeignKey found = fks.stream()
                    .filter(f -> f.column().equalsIgnoreCase(want.column())
                            && f.referencedTable().equalsIgnoreCase(want.referencedTable()))
                    .findFirst().orElse(null);
            String ref = spec.name() + "." + want.column();
            if (found == null) {
                problems.add(ref + ": FK → " + want.referencedTable() + " yok");
            } else if (want.deleteRule() != null
                    && !want.deleteRule().equalsIgnoreCase(normalizeRule(found.deleteRule()))) {
                problems.add(ref + ": ON DELETE " + want.deleteRule() + " bekleniyor, bulunan "
                        + (found.deleteRule() == null ? "?" : found.deleteRule()));
            }
        }
    }

    private static void validateChecks(SchemaInspector inspector, TableSpec spec, List<String> problems) {
        if (spec.requiredChecks().isEmpty()) return;
        List<String> clauses = inspector.checkClauses(spec.name());
        for (String want : spec.requiredChecks()) {
            String w = JdbcSchemaInspector.normalizeClause(want);
            boolean ok = clauses.stream().anyMatch(c -> c.contains(w));
            if (!ok) problems.add(spec.name() + ": CHECK (" + want + ") yok");
        }
    }

    /**
     * Doğrular; uyumluysa YALNIZ V001'i uygulanmış işaretler. V002+ bekleyen kalır.
     *
     * @throws SchemaMigrator.MigrationException uyumsuzluk (kayıt YOK), baseline yoksa,
     *                                           veya zaten versiyonlanmış DB
     */
    public static int adopt(Connection connection, SchemaInspector inspector,
                            List<Migration> migrations, Map<String, TableSpec> expectations) {
        Migration baseline = migrations.stream()
                .filter(m -> m.version() == BASELINE_VERSION).findFirst()
                .orElseThrow(() -> new SchemaMigrator.MigrationException(
                        "Baseline V" + BASELINE_VERSION + " bulunamadı — adoption yapılamaz"));
        SchemaMigrator migrator = new SchemaMigrator(connection, migrations);
        if (!migrator.readApplied().isEmpty()) {
            throw new SchemaMigrator.MigrationException(
                    "schema_version zaten kayıt içeriyor — adoption değil, --apply kullanın");
        }
        List<String> problems = validate(inspector, expectations);
        if (!problems.isEmpty()) {
            throw new SchemaMigrator.MigrationException(
                    "Adoption reddedildi — şema V001 ile uyumsuz (" + problems.size()
                            + " sorun), hiçbir kayıt yazılmadı:\n  - " + String.join("\n  - ", problems));
        }
        migrator.ensureVersionTable();
        migrator.recordApplied(baseline);
        LOG.info("Adoption tamam: V{} uygulanmış olarak işaretlendi; seed migration'ları --apply ile uygulanır",
                BASELINE_VERSION);
        return BASELINE_VERSION;
    }

    private static boolean hasUnique(List<Index> indexes, List<String> columns) {
        List<String> wanted = columns.stream().map(c -> c.toLowerCase(Locale.ROOT)).toList();
        for (Index idx : indexes) {
            if (!idx.unique()) continue;
            List<String> actual = idx.columns().stream().map(c -> c.toLowerCase(Locale.ROOT)).toList();
            if (actual.equals(wanted)) return true;
        }
        return false;
    }

    /** MySQL "NO ACTION" ile "RESTRICT" InnoDB'de eşdeğer davranır. */
    private static String normalizeRule(String rule) {
        if (rule == null) return null;
        String r = rule.toUpperCase(Locale.ROOT).trim();
        return "NO ACTION".equals(r) ? "RESTRICT" : r;
    }
}
