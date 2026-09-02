package service.db.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tek bir versiyonlu migration dosyası: {@code V###__aciklama.sql}.
 *
 * <p>Checksum, içerik satır sonları LF'ye normalize edildikten sonra alınan
 * SHA-256 (hex). Böylece Windows (CRLF) ve Linux (LF) checkout'ları aynı
 * checksum'ı üretir; yalnız gerçek içerik değişikliği "mismatch" sayılır.
 */
public record Migration(int version, String description, String sql, String checksum) {

    /** Dosya adı sözleşmesi — yalnız bu desen migration sayılır (legacy V2026_05_15__ gibi adlar eşleşmez). */
    public static final Pattern FILE_NAME = Pattern.compile("^V(\\d+)__([A-Za-z0-9_\\-]+)\\.sql$");

    public Migration {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(checksum, "checksum");
        if (version <= 0) throw new IllegalArgumentException("version > 0 olmalı: " + version);
    }

    /** Dosya adı + içerikten migration üretir; ad desene uymuyorsa {@code null}. */
    public static Migration fromFile(String fileName, String content) {
        Matcher m = FILE_NAME.matcher(fileName);
        if (!m.matches()) return null;
        int version = Integer.parseInt(m.group(1));
        String description = m.group(2).replace('_', ' ');
        String normalized = normalize(content);
        return new Migration(version, description, normalized, sha256Hex(normalized));
    }

    /** CRLF/CR → LF; BOM temizliği. */
    static String normalize(String content) {
        String s = content;
        if (!s.isEmpty() && s.charAt(0) == 0xFEFF) s = s.substring(1); // UTF-8 BOM
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 mevcut değil", e);
        }
    }

    @Override
    public String toString() {
        return "V" + version + " " + description;
    }
}
