package service.print;

/**
 * Yazıcı katmanından yukarı çıkan tüm hatalar bu sınıfla taşınır.
 * IOException, SocketTimeoutException vb. düşük seviyeli hataları sarmalar.
 */
public class PrinterException extends Exception {

    public PrinterException(String message) {
        super(message);
    }

    public PrinterException(String message, Throwable cause) {
        super(message, cause);
    }
}
