package service.util;

/**
 * PII (Kişisel Tanımlanabilir Bilgi) maskeleme yardımcıları.
 *
 * <p>Log'lara, hata mesajlarına veya UI dışına çıkacak metinlerde hassas
 * verileri kısaltır. Asla orijinal değeri ifşa etmez.
 */
public final class Mask {

    private Mask() {}

    /**
     * Telefon numarasını maskele. Son 4 hane görünür, gerisi yıldız.
     * <pre>maskPhone("05551234567") = "*******4567"</pre>
     */
    public static String phone(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("\\D", "");
        if (digits.isEmpty()) return "***";
        if (digits.length() <= 4) return "***" + digits;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length() - 4; i++) sb.append('*');
        sb.append(digits.substring(digits.length() - 4));
        return sb.toString();
    }

    /**
     * E-posta adresi maskele. Yerel bölümün ilk harfi ve "@" sonrası görünür.
     * <pre>maskEmail("ozelmail@gmail.com") = "o****@gmail.com"</pre>
     */
    public static String email(String s) {
        if (s == null) return null;
        int at = s.indexOf('@');
        if (at <= 0) return "***";
        String local = s.substring(0, at);
        String domain = s.substring(at);
        String maskedLocal = local.length() <= 1 ? "*" :
                local.charAt(0) + "****";
        return maskedLocal + domain;
    }

    /**
     * Kart numarasını maskele. Sadece son 4 hane görünür.
     * <pre>maskCard("4111 1111 1111 1234") = "****1234"</pre>
     */
    public static String card(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("\\D", "");
        if (digits.length() < 4) return "****";
        return "****" + digits.substring(digits.length() - 4);
    }

    /** Token / API key — tamamen "<masked>". */
    public static String token(String s) {
        return "<masked>";
    }

    /** Kullanıcı adı — log için orta kısmı yıldız. */
    public static String user(String s) {
        if (s == null || s.isEmpty()) return "?";
        if (s.length() <= 2) return s.charAt(0) + "*";
        return s.charAt(0) + "***" + s.charAt(s.length() - 1);
    }

    /** Genel kısa maskelele — sadece ilk ve son karakter. */
    public static String shortMask(String s) {
        if (s == null) return null;
        if (s.length() <= 2) return "**";
        return s.charAt(0) + "***" + s.charAt(s.length() - 1);
    }

    /**
     * JDBC URL içindeki {@code user=...} / {@code password=...} query
     * parametrelerini maskele. Gövde {@code DataConnection.Db}'den birebir
     * taşındı (CI testleri DB bootstrap'ı tetiklemesin diye); Db tarafı
     * geriye dönük uyumluluk için buraya delege eder.
     */
    public static String urlSecrets(String url) {
        if (url == null || url.isEmpty()) return url == null ? "" : url;
        // user=... ve password=... parametrelerini bul ve maskele
        String out = url.replaceAll("(?i)(password=)([^&]*)", "$1****");
        out = out.replaceAll("(?i)(user=)([^&]*)", "$1****");
        return out;
    }
}
