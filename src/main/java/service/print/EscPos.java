package service.print;

/**
 * ESC/POS standart komut kümesinin küçük bir alt kümesi.
 *
 * <p>Bu protokol Epson tarafından geliştirildi, fakat Bixolon, Star,
 * Xprinter, Posiflex, Citizen vd. tüm termal fiş yazıcılar tarafından
 * birinci sınıf destekleniyor. Bu sınıfın komutları "raw byte" — yani
 * yazıcı sürücüsüne ihtiyaç duymadan TCP soketine direkt yazılır.
 *
 * <p>Referans: Epson ESC/POS Application Programming Guide.
 *
 * <p><b>Karakter kodlaması:</b> Türkçe karakterler için
 * {@link #CMD_SET_CODE_PAGE_CP857} (CP857 — Turkish DOS) kullanılır.
 * Yazıcıya gönderilen metin {@code "CP857"} ile encode edilmelidir.
 */
public final class EscPos {

    private EscPos() {}

    // --- Kontrol baytları ----
    public static final byte ESC = 0x1B;
    public static final byte GS  = 0x1D;
    public static final byte LF  = 0x0A;
    public static final byte FF  = 0x0C;

    // --- Init / reset (ESC @) ---
    public static final byte[] CMD_INIT = { ESC, '@' };

    // --- Kod sayfası: ESC t n. n=12 → CP857 Turkish. ---
    public static final byte[] CMD_SET_CODE_PAGE_CP857 = { ESC, 't', 12 };

    // --- Karakter seti (uluslararası): ESC R n. n=12 → Latin/Türkiye ---
    public static final byte[] CMD_INTL_CHARSET_TR = { ESC, 'R', 12 };

    // --- Hizalama (ESC a n)  0=left 1=center 2=right ---
    public static final byte[] CMD_ALIGN_LEFT   = { ESC, 'a', 0 };
    public static final byte[] CMD_ALIGN_CENTER = { ESC, 'a', 1 };
    public static final byte[] CMD_ALIGN_RIGHT  = { ESC, 'a', 2 };

    // --- Vurgu (Bold) ESC E n ---
    public static final byte[] CMD_BOLD_ON  = { ESC, 'E', 1 };
    public static final byte[] CMD_BOLD_OFF = { ESC, 'E', 0 };

    // --- Çift boyut (GS ! n) ---
    public static final byte[] CMD_TXT_NORMAL = { GS, '!', 0x00 };
    public static final byte[] CMD_TXT_DOUBLE_HEIGHT = { GS, '!', 0x01 };
    public static final byte[] CMD_TXT_DOUBLE_WIDTH  = { GS, '!', 0x10 };
    public static final byte[] CMD_TXT_DOUBLE_BOTH   = { GS, '!', 0x11 };

    // --- Underline (ESC - n) 0=off 1=1dot ---
    public static final byte[] CMD_UNDERLINE_ON  = { ESC, '-', 1 };
    public static final byte[] CMD_UNDERLINE_OFF = { ESC, '-', 0 };

    // --- Kağıt besleme (ESC d n) ---
    public static byte[] feed(int lines) {
        if (lines < 0 || lines > 255) {
            throw new IllegalArgumentException("feed: 0..255");
        }
        return new byte[] { ESC, 'd', (byte) lines };
    }

    // --- Kesim (GS V m). 0=tam, 1=kısmi. ---
    public static final byte[] CMD_CUT_FULL    = { GS, 'V', 0 };
    public static final byte[] CMD_CUT_PARTIAL = { GS, 'V', 1 };

    // --- Beeper (ESC B n t) — bazı modellerde ---
    public static byte[] beep(int count, int duration) {
        return new byte[] { ESC, 'B', (byte) count, (byte) duration };
    }

    /** Java karakter dizisini CP857 (Turkish) bytelarına çevirir. */
    public static byte[] toCp857(String s) {
        if (s == null) return new byte[0];
        try {
            return s.getBytes("Cp857");
        } catch (java.io.UnsupportedEncodingException e) {
            // CP857 destekleyen JVM'lerde tetiklenmez; emniyet için ASCII'ye düşelim
            return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        }
    }
}
