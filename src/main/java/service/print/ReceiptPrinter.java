package service.print;

/**
 * Fişi fiziksel donanıma (ya da test çift'ine) basacak şeyin sözleşmesi.
 *
 * <p>İki implementasyonumuz var:
 * <ul>
 *   <li>{@link TcpEscPosPrinter} — gerçek ESC/POS uyumlu yazıcı (LAN/Wi-Fi).</li>
 *   <li>{@code NoopPrinter} — testlerde / yazıcı yokken sessiz kalır.</li>
 * </ul>
 */
public interface ReceiptPrinter {

    /**
     * Fişi yazıcıya gönderir. Sync — geri dönerse fiş bastı veya istisna fırlatıldı.
     *
     * @throws PrinterException ağ, kağıt veya yazıcı hatası
     */
    void print(Receipt receipt) throws PrinterException;

    /** Yazıcı tanıtım kodu (log/UI'de görmek için). */
    String code();

    /**
     * Mutfak fiş başlığı gibi şeyler için canlı yazıcı adı.
     * Default code()'a düşer.
     */
    default String displayName() {
        return code();
    }
}
