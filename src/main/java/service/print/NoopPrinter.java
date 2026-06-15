package service.print;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hiçbir yere basmaz, sadece log yazar.
 *
 * <p>Donanım gelmeden uygulamayı kırılmadan çalıştırmak ve
 * birim testler içindir.
 */
public class NoopPrinter implements ReceiptPrinter {

    private static final Logger LOG = LoggerFactory.getLogger(NoopPrinter.class);

    private final String code;
    private final String displayName;

    public NoopPrinter(String code) {
        this(code, code);
    }

    public NoopPrinter(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    @Override
    public void print(Receipt receipt) {
        LOG.info("[NOOP] {} → {}", code, receipt);
        for (Receipt.Line l : receipt.getLines()) {
            LOG.info("[NOOP]   {}", l);
        }
    }

    @Override public String code()        { return code; }
    @Override public String displayName() { return displayName; }
}
