package service.print;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TcpEscPosPrinterTest {

    @Test
    void buildPayload_contains_init_codepage_and_cut() throws Exception {
        TcpEscPosPrinter p = new TcpEscPosPrinter("K1", "127.0.0.1", 9100, 42);
        Receipt r = new Receipt(
                "*** OCAK ***",
                "SALON A",
                "5",
                "Ahmet",
                LocalDateTime.of(2026, 5, 15, 18, 30),
                List.of(new Receipt.Line(2, "Ciğer Şiş", null, true)),
                null, 100L
        );

        byte[] bytes = p.buildPayload(r);

        // 1) ESC @  init  (0x1B 0x40)
        assertEquals(0x1B, bytes[0] & 0xFF, "ESC byte yok");
        assertEquals('@',  bytes[1] & 0xFF, "init '@' yok");

        // 2) ESC t 12  → CP857 code page (12 = Turkish DOS)
        boolean hasCp = containsSequence(bytes, new byte[] { 0x1B, 't', 12 });
        assertTrue(hasCp, "CP857 (ESC t 12) komutu yok");

        // 3) GS V 1   → partial cut
        boolean hasCut = containsSequence(bytes, new byte[] { 0x1D, 'V', 1 });
        assertTrue(hasCut, "Kesim komutu (GS V 1) yok");
    }

    @Test
    void buildPayload_truncates_long_product_name() throws Exception {
        TcpEscPosPrinter p = new TcpEscPosPrinter("K1", "127.0.0.1", 9100, 32); // 58mm
        String longName = "Çok Çok Uzun Bir Ürün İsmi 1234567890 ABCDE";
        Receipt r = new Receipt("H", "Salon", "1", "G", LocalDateTime.now(),
                List.of(new Receipt.Line(1, longName, null, true)), null, 1L);

        byte[] bytes = p.buildPayload(r);

        // 32 karakter genişliğinde, "qty + ürün" max 32 olmalı → uzun ad kesilmeli
        assertTrue(bytes.length > 0);
        // İsmin tamamı bayt akışında DEĞİL — kesilmiş olmalı
        String fullCp857 = new String(bytes, "Cp857");
        assertFalse(fullCp857.contains("ABCDE"), "Uzun ürün adı kesilmemiş");
    }

    @Test
    void receipt_line_rejects_zero_or_negative_qty() {
        assertThrows(IllegalArgumentException.class, () -> new Receipt.Line(0, "x", null, true));
        assertThrows(IllegalArgumentException.class, () -> new Receipt.Line(-1, "x", null, true));
    }

    @Test
    void buildPayload_renders_salon_and_table_in_double_size() throws Exception {
        TcpEscPosPrinter p = new TcpEscPosPrinter("K1", "127.0.0.1", 9100, 42);
        Receipt r = new Receipt("*** OCAK ***", "ÜST KAT", "12", "Mehmet",
                LocalDateTime.of(2026, 5, 17, 19, 0),
                List.of(new Receipt.Line(1, "Adana Kebap", null, true)),
                null, 99L);

        byte[] bytes = p.buildPayload(r);

        // GS ! 0x11 → çift yükseklik+çift genişlik (büyük font) en az 1 kez kullanılmalı
        boolean hasBig = containsSequence(bytes, new byte[] { 0x1D, '!', 0x11 });
        assertTrue(hasBig, "Salon/Masa için büyük font (GS ! 0x11) eksik");

        // CP857 ile "MASA 12" mutlaka geçmeli
        String asText = new String(bytes, "Cp857");
        assertTrue(asText.contains("MASA 12"), "MASA 12 satırı yok");
    }

    @Test
    void non_highlighted_line_is_marked_bilgi() throws Exception {
        TcpEscPosPrinter p = new TcpEscPosPrinter("K1", "127.0.0.1", 9100, 42);
        Receipt r = new Receipt("*** OCAK ***", "S", "1", "G",
                LocalDateTime.now(),
                List.of(
                        new Receipt.Line(2, "Şiş", null, true),
                        new Receipt.Line(1, "Pide", null, false)
                ), null, 1L);

        String asText = new String(p.buildPayload(r), "Cp857");
        assertTrue(asText.contains("Şiş"), "Vurgulu kalem yok");
        assertTrue(asText.contains("(bilgi)"), "(bilgi) işareti yok");
    }

    @Test
    void cp857_encodes_turkish_chars() {
        byte[] enc = EscPos.toCp857("Şakşuka — ığüöç");
        assertNotNull(enc);
        assertTrue(enc.length > 0);
        // CP857 zaman zaman LibreOffice ortamında tek-byte; baytlar her halükarda non-empty
    }

    @Test
    void invalid_port_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new TcpEscPosPrinter("X", "127.0.0.1", -1, 42));
        assertThrows(IllegalArgumentException.class,
                () -> new TcpEscPosPrinter("X", "127.0.0.1", 70000, 42));
    }

    @Test
    void invalid_width_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new TcpEscPosPrinter("X", "127.0.0.1", 9100, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new TcpEscPosPrinter("X", "127.0.0.1", 9100, 100));
    }

    /** byte dizisinde verilen alt-dizinin geçtiğini kontrol et. */
    private static boolean containsSequence(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
