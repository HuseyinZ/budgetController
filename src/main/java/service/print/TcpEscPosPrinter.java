package service.print;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;

/**
 * Ağ (LAN/Wi-Fi) üstünden ESC/POS uyumlu termal fiş yazıcılarına basar.
 *
 * <p>Kullanım:
 * <pre>{@code
 *   ReceiptPrinter p = new TcpEscPosPrinter("KITCHEN_1", "192.168.1.241", 9100, 42);
 *   p.print(receipt);
 * }</pre>
 *
 * <p>Yazıcının LAN/Wi-Fi modülü TCP 9100 portunda dinler (de facto standart).
 * Sürücü kurulumuna gerek yoktur — raw byte gönderiyoruz.
 *
 * <p>Bu sınıf <b>thread-safe değildir</b> ama her çağrıda kısa ömürlü Socket açar.
 * Aynı anda farklı thread'lerden çağrı güvenlidir.
 */
public class TcpEscPosPrinter implements ReceiptPrinter {

    private static final Logger LOG = LoggerFactory.getLogger(TcpEscPosPrinter.class);
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;
    static final int DEFAULT_WRITE_TIMEOUT_MS = 5000;

    private final String code;
    private final String displayName;
    private final String host;
    private final int port;
    private final int charsPerLine;
    private final int connectTimeoutMs;
    private final int writeTimeoutMs;

    public TcpEscPosPrinter(String code, String host, int port, int charsPerLine) {
        this(code, code, host, port, charsPerLine, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_WRITE_TIMEOUT_MS);
    }

    public TcpEscPosPrinter(String code,
                            String displayName,
                            String host,
                            int port,
                            int charsPerLine,
                            int connectTimeoutMs,
                            int writeTimeoutMs) {
        this.code = Objects.requireNonNull(code, "code");
        this.displayName = Objects.requireNonNullElse(displayName, code);
        this.host = Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("port aralık dışı: " + port);
        this.port = port;
        if (charsPerLine < 16 || charsPerLine > 80) {
            throw new IllegalArgumentException("charsPerLine 16..80 arası olmalı (80mm yazıcı: 42, 58mm: 32)");
        }
        this.charsPerLine = charsPerLine;
        this.connectTimeoutMs = connectTimeoutMs;
        this.writeTimeoutMs = writeTimeoutMs;
    }

    @Override public String code()         { return code; }
    @Override public String displayName()  { return displayName; }

    @Override
    public void print(Receipt receipt) throws PrinterException {
        Objects.requireNonNull(receipt, "receipt");
        byte[] payload = buildPayload(receipt);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(writeTimeoutMs);
            try (OutputStream out = socket.getOutputStream()) {
                out.write(payload);
                out.flush();
            }
            LOG.info("Fiş basıldı  printer={}  order={}  bytes={}", code, receipt.getOrderId(), payload.length);
        } catch (SocketException e) {
            throw new PrinterException("Yazıcıya erişilemiyor (kapalı, kağıt sıkışmış veya ağ dışı): "
                    + host + ":" + port, e);
        } catch (IOException e) {
            throw new PrinterException("Yazıcı G/Ç hatası: " + host + ":" + port, e);
        }
    }

    /**
     * Receipt nesnesini bayt akışına dönüştürür.
     *
     * <p>Düzen:
     * <pre>
     *   *** MUTFAK ADI ***          ← Header (çift boyut, kalın, ortalı)
     *   =============================
     *           SALON A             ← Salon adı (DEV — 2x2 font)
     *           MASA 5              ← Masa no  (DEV — 2x2 font)
     *   =============================
     *   Garson : Ahmet
     *   Saat   : 17.05.2026 18:30
     *   Fiş No : #12345
     *   -----------------------------
     *    2 x Adana Kebap            ← vurgulu (çift yükseklik)
     *      > Acılı
     *    1 x (bilgi) Ayran          ← küçük, info
     *   -----------------------------
     *   NOT: Acele lütfen
     * </pre>
     *
     * <b>Package-private</b> — testlerden doğrulamak için.
     */
    byte[] buildPayload(Receipt r) throws PrinterException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(640);
        try {
            // Init + Türkçe code page
            buf.write(EscPos.CMD_INIT);
            buf.write(EscPos.CMD_SET_CODE_PAGE_CP857);
            buf.write(EscPos.CMD_INTL_CHARSET_TR);

            // Üst başlık (mutfak adı — çift boyut, kalın, ortalı)
            writeCenteredBoldDoubleSizeBlock(buf, r.getHeader());

            writeLine(buf, "=".repeat(charsPerLine));

            // -------- SALON ADI ve MASA NO (en büyük font) --------
            if (r.getSalonName() != null && !r.getSalonName().isBlank()) {
                writeCenteredBoldDoubleSizeBlock(buf, r.getSalonName(), "MASA " + r.getTableNo());
            } else {
                writeCenteredBoldDoubleSizeBlock(buf, "MASA " + r.getTableNo());
            }
            writeLine(buf, "=".repeat(charsPerLine));

            // -------- Meta bilgiler --------
            buf.write(EscPos.CMD_ALIGN_LEFT);
            writeLine(buf, "Garson : " + r.getWaiterName());
            writeLine(buf, "Saat   : " + r.getTimeFormatted());
            if (r.getOrderId() != null) {
                writeLine(buf, "Fis No : #" + r.getOrderId());
            }
            writeLine(buf, "-".repeat(charsPerLine));

            // -------- Kalemler --------
            for (Receipt.Line line : r.getLines()) {
                if (line.isHighlighted()) {
                    // Bu mutfağın yapacağı kalem — büyük, kalın
                    buf.write(EscPos.CMD_TXT_DOUBLE_HEIGHT);
                    buf.write(EscPos.CMD_BOLD_ON);
                    String qty = String.format("%2d x ", line.getQuantity());
                    writeLine(buf, qty + safeProduct(line.getProductName(), charsPerLine - 5));
                    buf.write(EscPos.CMD_BOLD_OFF);
                    buf.write(EscPos.CMD_TXT_NORMAL);
                    if (line.getNote() != null && !line.getNote().isBlank()) {
                        writeLine(buf, "      > " + line.getNote());
                    }
                } else {
                    // Bilgi amaçlı (başka mutfak hazırlayacak) — küçük, normal
                    String qty = String.format("(bilgi) %2d x ", line.getQuantity());
                    String nameMax = safeProduct(line.getProductName(), Math.max(8, charsPerLine - qty.length()));
                    writeLine(buf, qty + nameMax);
                }
            }

            writeLine(buf, "-".repeat(charsPerLine));

            // Genel sipariş notu (opsiyonel)
            if (r.getOrderNote() != null && !r.getOrderNote().isBlank()) {
                buf.write(EscPos.CMD_BOLD_ON);
                writeLine(buf, "NOT: " + r.getOrderNote());
                buf.write(EscPos.CMD_BOLD_OFF);
            }

            // Boşluk + kesim
            buf.write(EscPos.feed(4));
            buf.write(EscPos.CMD_CUT_PARTIAL);

            // Mutfak duyumu için buzzer — yazıcı modeli destekliyorsa
            // ESC B 3 3 → 3 kez beep, 150 ms (yazıcı buzzer'ı).
            buf.write(EscPos.beep(3, 3));
        } catch (IOException e) {
            throw new PrinterException("Fiş bayt akışı oluşturulamadı", e);
        }
        return buf.toByteArray();
    }

    // -------- iç yardımcılar --------

    private static void writeCenteredBoldDoubleSizeBlock(ByteArrayOutputStream buf, String... lines)
            throws IOException {
        buf.write(EscPos.CMD_ALIGN_CENTER);
        buf.write(EscPos.CMD_BOLD_ON);
        buf.write(EscPos.CMD_TXT_DOUBLE_BOTH);
        for (String line : lines) {
            writeLine(buf, line);
        }
        buf.write(EscPos.CMD_TXT_NORMAL);
        buf.write(EscPos.CMD_BOLD_OFF);
    }

    private static void writeLine(ByteArrayOutputStream buf, String s) throws IOException {
        buf.write(EscPos.toCp857(s == null ? "" : s));
        buf.write(EscPos.LF);
    }

    private static String safeProduct(String name, int maxLen) {
        if (name == null) return "";
        return name.length() <= maxLen ? name : name.substring(0, maxLen);
    }
}
