package service.db;

import DataConnection.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Uygulama başlangıcında çalışan en küçük şema patch'leri.
 *
 * <p>Bu yardımcı sınıf bilinen sorunlu CHECK constraint'leri kaldırır:
 * <ul>
 *   <li>{@code products_chk_1}, {@code products_chk_2}, vb. — eski şemada
 *       {@code stock >= 0} veya {@code vat_rate >= 0} gibi CHECK'ler tanımlı
 *       olabilir. Uygulamamız stok yönetimi yapmadığı için sipariş eklemede
 *       bunlar patlıyor → tek seferde drop ediyoruz.</li>
 * </ul>
 *
 * <p>Best-effort: hata olursa sessiz geçer. Drop yapıldıktan sonra constraint
 * tekrar yaratılmaz (DB sahibi isteyerek eklerse o ayrı iş).
 */
public final class SchemaPatcher {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaPatcher.class);

    private SchemaPatcher() {}

    /** Tüm patch'leri uygula — uygulama başlangıcında çağrılır. */
    public static void applyAll() {
        try (Connection c = Db.getConnection()) {
            dropProductsCheckConstraints(c);
            normalizeNegativeStock(c);
            ensureReservationsTable(c);
        } catch (SQLException ex) {
            LOG.warn("Schema patcher: bağlantı kurulamadı — {}", ex.getMessage());
        }
    }

    /**
     * Masa rezervasyonları tablosunu oluşturur (yoksa).
     * <pre>
     * CREATE TABLE IF NOT EXISTS reservations (
     *   id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
     *   table_no        INT          NOT NULL,
     *   start_time      DATETIME     NOT NULL,
     *   end_time        DATETIME     NOT NULL,
     *   customer_name   VARCHAR(120) NOT NULL,
     *   customer_phone  VARCHAR(40),
     *   party_size      INT          DEFAULT 1,
     *   notes           VARCHAR(500),
     *   status          VARCHAR(20)  DEFAULT 'BOOKED', -- BOOKED | CANCELLED | SEATED | NO_SHOW
     *   created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
     *   created_by      VARCHAR(80)
     * )
     * </pre>
     */
    private static void ensureReservationsTable(Connection c) {
        final String ddl =
                "CREATE TABLE IF NOT EXISTS reservations (" +
                "  id              BIGINT       AUTO_INCREMENT PRIMARY KEY," +
                "  table_no        INT          NOT NULL," +
                "  start_time      DATETIME     NOT NULL," +
                "  end_time        DATETIME     NOT NULL," +
                "  customer_name   VARCHAR(120) NOT NULL," +
                "  customer_phone  VARCHAR(40)," +
                "  party_size      INT          DEFAULT 1," +
                "  notes           VARCHAR(500)," +
                "  status          VARCHAR(20)  DEFAULT 'BOOKED'," +
                "  created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP," +
                "  created_by      VARCHAR(80)," +
                "  INDEX idx_reservations_table_time (table_no, start_time, end_time)," +
                "  INDEX idx_reservations_status (status)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        try (Statement st = c.createStatement()) {
            st.executeUpdate(ddl);
            LOG.info("Schema patch: reservations tablosu hazır");
        } catch (SQLException ex) {
            LOG.warn("reservations tablosu oluşturulamadı: {}", ex.getMessage());
        }
    }

    /**
     * DB'de eski siparişlerden dolayı negatif kalmış stok değerlerini 0'a çeker.
     * Önce information_schema'dan mevcut sütun adını bulur (stock / stock_qty / quantity).
     */
    private static void normalizeNegativeStock(Connection c) {
        // 1. Hangi stok sütunu var?
        String stockColumn = findExistingColumn(c, "products",
                new String[]{"stock", "stock_qty", "quantity", "qty"});
        if (stockColumn == null) {
            // Stok sütunu yok → bir şey yapma
            return;
        }
        // 2. UPDATE çalıştır
        try (Statement st = c.createStatement()) {
            int rows = st.executeUpdate(
                    "UPDATE products SET `" + stockColumn + "` = 0 WHERE `"
                            + stockColumn + "` < 0");
            if (rows > 0) {
                LOG.info("Schema patch: products.{} sütununda {} negatif stok 0'a çekildi",
                        stockColumn, rows);
            }
        } catch (SQLException ex) {
            LOG.debug("Negatif stok normalize edilemedi ({}): {}", stockColumn, ex.getMessage());
        }
    }

    /**
     * Verilen adaylar arasından tabloda var olan ilk sütun adını döner.
     * information_schema'dan sorgular.
     */
    private static String findExistingColumn(Connection c, String table, String[] candidates) {
        final String sql =
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        java.util.Set<String> existing = new java.util.HashSet<>();
        try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    existing.add(rs.getString(1).toLowerCase());
                }
            }
        } catch (SQLException ex) {
            return null;
        }
        for (String cand : candidates) {
            if (existing.contains(cand.toLowerCase())) return cand;
        }
        return null;
    }

    /**
     * products tablosundaki tüm CHECK constraint'leri kaldırır.
     * <p>information_schema.CHECK_CONSTRAINTS'ten okur, her birini ayrı ayrı
     * ALTER TABLE ile drop eder. Hata olursa sıradakine geçer.
     */
    private static void dropProductsCheckConstraints(Connection c) {
        // Önce constraint isimlerini topla
        List<String> names = new ArrayList<>();
        final String selectSql =
                "SELECT cc.CONSTRAINT_NAME " +
                "FROM information_schema.CHECK_CONSTRAINTS cc " +
                "JOIN information_schema.TABLE_CONSTRAINTS tc " +
                "  ON cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME " +
                " AND cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA " +
                "WHERE tc.TABLE_NAME = 'products' " +
                "  AND tc.CONSTRAINT_SCHEMA = DATABASE() " +
                "  AND tc.CONSTRAINT_TYPE = 'CHECK'";
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(selectSql)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        } catch (SQLException ex) {
            LOG.debug("CHECK constraint listesi alınamadı: {}", ex.getMessage());
            return;
        }

        if (names.isEmpty()) return;
        int dropped = 0;
        for (String name : names) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE products DROP CHECK `" + name + "`");
                dropped++;
                LOG.info("Schema patch: products.{} CHECK constraint kaldırıldı", name);
            } catch (SQLException ex) {
                LOG.debug("CHECK '{}' drop edilemedi: {}", name, ex.getMessage());
            }
        }
        if (dropped > 0) {
            LOG.info("Schema patch tamam: products tablosundan {} CHECK constraint kaldırıldı", dropped);
        }
    }
}
