package service.db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * MySQL {@code information_schema} üzerinden şema okur — yalnız SELECT, hiçbir
 * şey yazmaz. Bağlantının seçili veritabanı ({@code DATABASE()}) esas alınır.
 * Runtime kullanıcısının (SELECT yetkisi) çalıştırabileceği sorgulardır.
 */
public final class JdbcSchemaInspector implements SchemaInspector {

    private final Connection connection;

    public JdbcSchemaInspector(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public boolean tableExists(String table) {
        String sql = "SELECT 1 FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, lower(table));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw fail("tablo", table, e);
        }
    }

    @Override
    public Map<String, Column> columns(String table) {
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, EXTRA "
                + "FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) = ? ORDER BY ORDINAL_POSITION";
        Map<String, Column> out = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, lower(table));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = lower(rs.getString(1));
                    String extra = lower(rs.getString(5));
                    out.put(name, new Column(name, lower(rs.getString(2)), lower(rs.getString(3)),
                            "yes".equalsIgnoreCase(rs.getString(4)),
                            extra != null && extra.contains("auto_increment"),
                            extra != null && extra.contains("generated")));
                }
            }
        } catch (SQLException e) {
            throw fail("kolon", table, e);
        }
        return out;
    }

    @Override
    public List<Index> indexes(String table) {
        String sql = "SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) = ? "
                + "ORDER BY INDEX_NAME, SEQ_IN_INDEX";
        Map<String, List<String>> cols = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, lower(table));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String idx = rs.getString(1);
                    cols.computeIfAbsent(idx, k -> new ArrayList<>()).add(lower(rs.getString(3)));
                    unique.put(idx, rs.getInt(2) == 0);
                }
            }
        } catch (SQLException e) {
            throw fail("index", table, e);
        }
        List<Index> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : cols.entrySet()) {
            out.add(new Index(e.getKey(), unique.get(e.getKey()), List.copyOf(e.getValue())));
        }
        return out;
    }

    @Override
    public List<ForeignKey> foreignKeys(String table) {
        String sql = "SELECT k.COLUMN_NAME, k.REFERENCED_TABLE_NAME, r.DELETE_RULE "
                + "FROM information_schema.KEY_COLUMN_USAGE k "
                + "JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
                + "  ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME "
                + "WHERE k.TABLE_SCHEMA = DATABASE() AND LOWER(k.TABLE_NAME) = ? "
                + "AND k.REFERENCED_TABLE_NAME IS NOT NULL";
        List<ForeignKey> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, lower(table));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ForeignKey(lower(rs.getString(1)), lower(rs.getString(2)),
                            upper(rs.getString(3))));
                }
            }
        } catch (SQLException e) {
            throw fail("FK", table, e);
        }
        return out;
    }

    @Override
    public List<String> checkClauses(String table) {
        // MySQL 8.0.16+: CHECK_CONSTRAINTS. Daha eski sürümde tablo yoksa boş liste döner.
        String sql = "SELECT cc.CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS cc "
                + "JOIN information_schema.TABLE_CONSTRAINTS tc "
                + "  ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME "
                + "WHERE tc.TABLE_SCHEMA = DATABASE() AND LOWER(tc.TABLE_NAME) = ? "
                + "AND tc.CONSTRAINT_TYPE = 'CHECK'";
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, lower(table));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(normalizeClause(rs.getString(1)));
                }
            }
        } catch (SQLException e) {
            throw fail("CHECK", table, e);
        }
        return out;
    }

    /** {@code (`quantity` > 0)} → {@code (quantity > 0)}; küçük harf, tek boşluk. */
    static String normalizeClause(String clause) {
        if (clause == null) return "";
        return clause.replace("`", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static SchemaMigrator.MigrationException fail(String what, String table, SQLException e) {
        return new SchemaMigrator.MigrationException(what + " sorgusu başarısız: " + table
                + " (SQLState=" + e.getSQLState() + ", vendorCode=" + e.getErrorCode() + ")", e);
    }

    private static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }
    private static String upper(String s) { return s == null ? null : s.toUpperCase(Locale.ROOT); }
}
