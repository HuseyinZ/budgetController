package service.db.migration;

import java.util.List;
import java.util.Map;

/**
 * Mevcut şemanın yapısını okuyan salt-okunur arayüz (adoption doğrulaması için).
 * Üretimde {@link JdbcSchemaInspector} (information_schema), testlerde bellek içi sahte.
 * Hiçbir implementasyon yazma yapmaz.
 */
public interface SchemaInspector {

    /**
     * Kolon bilgisi.
     *
     * @param name          küçük harf kolon adı
     * @param dataType      information_schema DATA_TYPE (int, varchar, enum, ...)
     * @param columnType    COLUMN_TYPE (örn. enum('A','B'), int, decimal(12,2))
     * @param nullable      IS_NULLABLE = YES
     * @param autoIncrement EXTRA içinde auto_increment
     * @param generated     EXTRA içinde GENERATED (STORED/VIRTUAL generated kolon)
     */
    record Column(String name, String dataType, String columnType,
                  boolean nullable, boolean autoIncrement, boolean generated) {}

    /** Index: ad, benzersiz mi, sıralı kolon adları (küçük harf). PRIMARY dahil. */
    record Index(String name, boolean unique, List<String> columns) {}

    /** Foreign key: kolon → hedef tablo; ON DELETE kuralı (CASCADE / RESTRICT / SET NULL / NO ACTION). */
    record ForeignKey(String column, String referencedTable, String deleteRule) {}

    boolean tableExists(String table);

    /** Kolonlar — ad→Column (küçük harf anahtar). Tablo yoksa boş. */
    Map<String, Column> columns(String table);

    List<Index> indexes(String table);

    List<ForeignKey> foreignKeys(String table);

    /**
     * Tabloya bağlı CHECK constraint ifadeleri — normalize edilmiş
     * (küçük harf, backtick'siz, tek boşluk). Örn. {@code (quantity > 0)}.
     */
    List<String> checkClauses(String table);
}
