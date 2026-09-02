package service.db.migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * V001 canonical şemasının YAPISAL beklentileri — {@code --adopt-existing}
 * doğrulaması için. Mevcut (elle evrilmiş) bir üretim şemasının V001 ile
 * davranışsal olarak uyumlu olup olmadığını, tabloyu yaratmadan karara bağlar.
 *
 * <p>Ne doğrulanır:
 * <ul>
 *   <li>tablo ve kolon varlığı, kolon <i>tip ailesi</i> (INT/BIGINT, DATETIME/TIMESTAMP,
 *       VARCHAR/TEXT eşdeğer — default/collation/uzunluk farkları adoption'ı bozmaz)</li>
 *   <li>ENUM'larda kodun kullandığı her değer</li>
 *   <li>PRIMARY KEY (id) ve id'de AUTO_INCREMENT</li>
 *   <li>kritik NOT NULL'lar (uygulama mantığının dayandığı kolonlar)</li>
 *   <li>kritik UNIQUE kümeleri, kritik FK hedefleri ve davranışı etkileyen ON DELETE kuralları</li>
 *   <li>order_items generated kolonlarının gerçekten GENERATED olması</li>
 *   <li>V001'de bilinçli korunan CHECK ({@code quantity > 0})</li>
 * </ul>
 *
 * <p>{@code SchemaAdoptionTest} bu spec'in V001 dosyasındaki tablo/kolon adlarıyla
 * birebir senkron olduğunu doğrular.
 */
public final class BaselineExpectations {

    public enum Family { INTEGER, DECIMAL, TEXT, TEMPORAL, DATE, ENUM }

    /**
     * @param notNull       kritik NOT NULL beklentisi (false → nullability kontrol edilmez)
     * @param autoIncrement AUTO_INCREMENT beklentisi
     * @param generated     GENERATED kolon beklentisi
     */
    public record ColumnSpec(String name, Family family, List<String> enumValues,
                             boolean notNull, boolean autoIncrement, boolean generated) {
        /** Fluent DSL: kritik NOT NULL işaretli kopya. (Ad, {@code notNull()} accessor'ıyla ÇAKIŞMAMALI.) */
        ColumnSpec required() { return new ColumnSpec(name, family, enumValues, true, autoIncrement, generated); }
    }

    /** @param deleteRule beklenen ON DELETE (null → yalnız varlık kontrolü) */
    public record FkSpec(String column, String referencedTable, String deleteRule) {}

    /**
     * @param uniqueColumnSets kritik UNIQUE kolon kümeleri (PRIMARY ayrıca zorunlu)
     * @param requiredChecks   normalize edilmiş CHECK ifadesi parçaları (tam eşleşme değil, içerme)
     */
    public record TableSpec(String name,
                            Map<String, ColumnSpec> columns,
                            List<List<String>> uniqueColumnSets,
                            List<FkSpec> foreignKeys,
                            List<String> requiredChecks) {}

    private BaselineExpectations() {}

    /** DATA_TYPE → aile; bilinmeyen tip null. */
    public static Family familyOf(String dataType) {
        if (dataType == null) return null;
        return switch (dataType.toLowerCase(Locale.ROOT)) {
            case "tinyint", "smallint", "mediumint", "int", "integer", "bigint" -> Family.INTEGER;
            case "decimal", "numeric" -> Family.DECIMAL;
            case "varchar", "char", "text", "tinytext", "mediumtext", "longtext" -> Family.TEXT;
            case "datetime", "timestamp" -> Family.TEMPORAL;
            case "date" -> Family.DATE;
            case "enum" -> Family.ENUM;
            default -> null;
        };
    }

    public static Map<String, TableSpec> baseline() {
        Map<String, TableSpec> t = new LinkedHashMap<>();

        table(t, "roles",
                cols(id(), s("name").required(), d("created_at")),
                uniques(set("name")), fks(), checks());

        table(t, "users",
                cols(id(), s("username").required(), s("password_hash"), s("full_name"), i("role_id").required(),
                        i("is_active").required(), d("created_at"), d("updated_at")),
                uniques(set("username")), fks(fk("role_id", "roles", null)), checks());

        table(t, "categories",
                cols(id(), s("name").required(), d("created_at"), d("updated_at")),
                uniques(set("name")), fks(), checks());

        table(t, "products",
                cols(id(), s("name").required(), dec("unit_price").required(), i("stock_qty"), i("category_id"),
                        i("is_active").required(), i("pieces_per_portion"), s("unit_label"),
                        d("created_at"), d("updated_at")),
                uniques(), fks(), checks());

        table(t, "dining_tables",
                cols(id(), i("table_no").required(), e("status", "EMPTY", "OCCUPIED", "RESERVED").required(),
                        s("note"), d("created_at"), d("updated_at")),
                uniques(set("table_no")), fks(), checks());

        table(t, "kitchen_printers",
                cols(id(), s("code").required(), s("display_name").required(), s("host").required(),
                        i("port").required(), i("char_per_line"), i("code_page"), i("is_active").required(),
                        s("note"), d("created_at"), d("updated_at")),
                uniques(set("code")), fks(), checks());

        table(t, "orders",
                cols(id(), i("table_id"), i("waiter_id"), s("note"),
                        e("status", "PENDING", "IN_PROGRESS", "READY", "COMPLETED", "CANCELLED").required(),
                        dec("subtotal"), dec("tax_total"), dec("discount_total"), dec("total"),
                        d("order_date"), d("closed_at"), d("created_at"), d("updated_at")),
                uniques(), fks(), checks());

        table(t, "order_items",
                cols(id(), i("order_id").required(), i("product_id").required(), s("product_name"),
                        i("quantity").required(), dec("unit_price").required(),
                        gen("net_amount"), gen("tax_amount"), gen("line_total"),
                        d("printed_at"), i("print_count"), i("kitchen_override_id"),
                        i("pieces_per_portion"), s("unit_label"), s("note"), d("created_at"), d("updated_at")),
                uniques(),
                fks(fk("order_id", "orders", "CASCADE"), fk("product_id", "products", "RESTRICT")),
                checks("quantity > 0"));

        table(t, "payments",
                cols(id(), i("order_id").required(), i("cashier_id"), dec("amount").required(),
                        e("method", "CASH", "CREDIT_CARD", "DEBIT_CARD", "TRANSFER", "ONLINE", "MIXED").required(),
                        d("paid_at"), d("created_at"), d("updated_at")),
                uniques(), fks(fk("order_id", "orders", null)), checks());

        table(t, "order_logs",
                cols(id(), i("order_id").required(), d("event_time"), s("message").required()),
                uniques(), fks(), checks());

        table(t, "expenses",
                cols(id(), date("expense_date").required(), dec("amount").required(), s("note"),
                        dec("quantity_kg"), dec("unit_price_per_kg"), d("created_at"), d("updated_at")),
                uniques(), fks(), checks());

        table(t, "refund_log",
                cols(id(), i("user_id"), s("user_name"), s("action_type").required(), i("table_no"),
                        i("order_id"), s("product_name"), i("quantity"), dec("amount"), s("reason"),
                        d("created_at")),
                uniques(), fks(), checks());

        table(t, "reservations",
                cols(id(), i("table_no").required(), d("start_time").required(), d("end_time").required(),
                        s("customer_name").required(), s("customer_phone"), i("party_size"), s("notes"),
                        s("status"), d("created_at"), s("created_by")),
                uniques(), fks(), checks());

        table(t, "category_printer_routes",
                cols(id(), i("category_id").required(), i("printer_id").required(), d("created_at")),
                uniques(set("category_id", "printer_id")),
                fks(fk("category_id", "categories", "CASCADE"), fk("printer_id", "kitchen_printers", "CASCADE")),
                checks());

        table(t, "print_jobs",
                cols(id(), i("order_id").required(), i("printer_id").required(), s("payload").required(),
                        e("status", "PENDING", "PRINTED", "FAILED").required(), i("attempts"), s("last_error"),
                        d("created_at"), d("printed_at")),
                uniques(),
                fks(fk("order_id", "orders", "CASCADE"), fk("printer_id", "kitchen_printers", null)),
                checks());

        table(t, "user_area_permissions",
                cols(id(), i("user_id").required(), s("building").required(), s("section").required(), d("created_at")),
                uniques(set("user_id", "building", "section")),
                fks(fk("user_id", "users", "CASCADE")), checks());

        return Collections.unmodifiableMap(t);
    }

    // ---- küçük DSL ----
    private static ColumnSpec col(String n, Family f) { return new ColumnSpec(n, f, List.of(), false, false, false); }
    private static ColumnSpec id()          { return new ColumnSpec("id", Family.INTEGER, List.of(), true, true, false); }
    private static ColumnSpec i(String n)   { return col(n, Family.INTEGER); }
    private static ColumnSpec s(String n)   { return col(n, Family.TEXT); }
    private static ColumnSpec dec(String n) { return col(n, Family.DECIMAL); }
    private static ColumnSpec gen(String n) { return new ColumnSpec(n, Family.DECIMAL, List.of(), false, false, true); }
    private static ColumnSpec d(String n)   { return col(n, Family.TEMPORAL); }
    private static ColumnSpec date(String n){ return col(n, Family.DATE); }
    private static ColumnSpec e(String n, String... values) {
        return new ColumnSpec(n, Family.ENUM, List.of(values), false, false, false);
    }
    private static Map<String, ColumnSpec> cols(ColumnSpec... specs) {
        Map<String, ColumnSpec> m = new LinkedHashMap<>();
        for (ColumnSpec c : specs) m.put(c.name(), c);
        return m;
    }
    private static List<String> set(String... c) { return List.of(c); }
    @SafeVarargs
    private static List<List<String>> uniques(List<String>... sets) { return List.of(sets); }
    private static FkSpec fk(String col, String ref, String deleteRule) { return new FkSpec(col, ref, deleteRule); }
    private static List<FkSpec> fks(FkSpec... f) { return List.of(f); }
    private static List<String> checks(String... c) { return List.of(c); }
    private static void table(Map<String, TableSpec> t, String name, Map<String, ColumnSpec> cols,
                              List<List<String>> uniques, List<FkSpec> fks, List<String> checks) {
        t.put(name, new TableSpec(name, Collections.unmodifiableMap(cols), uniques, fks, checks));
    }

    /** Test/rapor için: tüm tablo adları. */
    public static List<String> tableNames() { return new ArrayList<>(baseline().keySet()); }
}
