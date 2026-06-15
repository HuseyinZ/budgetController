package model;

/**
 * Veritabanı tablo karşılığı: kitchen_printers
 *
 * <p>Bir mutfak yazıcısının kalıcı tanımı. Çalışma anında
 * {@code service.print.TcpEscPosPrinter}'a çevrilir.
 */
public class KitchenPrinter extends BaseEntity {

    private String code;            // KITCHEN_1, KITCHEN_DONER
    private String displayName;     // "Mutfak 1 - Sıcak"
    private String host;            // IP veya hostname
    private int port = 9100;
    private int charPerLine = 42;   // 80mm yazıcı = 42, 58mm = 32
    private int codePage = 12;      // ESC t n  (12 = CP857 Turkish)
    private boolean active = true;
    private String note;

    public KitchenPrinter() {}

    public KitchenPrinter(String code, String displayName, String host) {
        setCode(code);
        setDisplayName(displayName);
        setHost(host);
    }

    public String getCode()                       { return code; }
    public void setCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code boş olamaz");
        }
        this.code = code.trim();
    }

    public String getDisplayName()                { return displayName; }
    public void setDisplayName(String displayName){ this.displayName = displayName; }

    public String getHost()                       { return host; }
    public void setHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host boş olamaz");
        }
        this.host = host.trim();
    }

    public int getPort()                          { return port; }
    public void setPort(int port) {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port aralık dışı: " + port);
        }
        this.port = port;
    }

    public int getCharPerLine()                   { return charPerLine; }
    public void setCharPerLine(int n) {
        if (n < 16 || n > 80) {
            throw new IllegalArgumentException("charPerLine 16..80 olmalı");
        }
        this.charPerLine = n;
    }

    public int getCodePage()                      { return codePage; }
    public void setCodePage(int codePage)         { this.codePage = codePage; }

    public boolean isActive()                     { return active; }
    public void setActive(boolean active)         { this.active = active; }

    public String getNote()                       { return note; }
    public void setNote(String note)              { this.note = note; }

    @Override
    public String toString() {
        return "KitchenPrinter{" + code + " @ " + host + ":" + port + "}";
    }
}
