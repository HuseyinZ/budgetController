package service.print;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dao.PrintJobDAO;
import dao.jdbc.PrintJobJdbcDAO;
import model.KitchenPrinter;
import model.OrderItem;
import model.PrintJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutfak fişi gönderiminin yüksek seviye orkestrasyonu.
 *
 * <p>Akış:
 * <ol>
 *   <li>{@link KitchenRouter} ile kalemler yazıcı bazında gruplanır.</li>
 *   <li>Her grup için {@link Receipt} hazırlanır.</li>
 *   <li>Yazıcının {@code host}/{@code port}'una göre runtime'da
 *       {@link TcpEscPosPrinter} cache'lenip çağrılır.</li>
 *   <li>Başarısız her gönderim {@code print_jobs} tablosuna düşer
 *       (yeniden deneme için).</li>
 * </ol>
 *
 * <p>Bu sınıf <b>thread-safe</b>: yazıcı önbelleği {@code ConcurrentHashMap}.
 */
public class PrintingService {

    private static final Logger LOG = LoggerFactory.getLogger(PrintingService.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final KitchenRouter router;
    private final PrintJobDAO printJobDAO;
    private final Map<Integer, ReceiptPrinter> printerCache = new ConcurrentHashMap<>();

    /** Test/DI için: önceden hazırlanmış yazıcı ataması. */
    private final Map<Integer, ReceiptPrinter> printerOverride;

    public PrintingService() {
        this(new KitchenRouter(), new PrintJobJdbcDAO(), null);
    }

    public PrintingService(KitchenRouter router,
                           PrintJobDAO printJobDAO,
                           Map<Integer, ReceiptPrinter> printerOverride) {
        this.router = router;
        this.printJobDAO = printJobDAO;
        this.printerOverride = printerOverride;
    }

    /**
     * Bir siparişin TÜM kalemlerini, ilgili her mutfak yazıcısına gönderir.
     *
     * <p><b>Önemli davranış:</b> Yazıcı başına ayrı bir fiş çıkarılır ve
     * o fişin içinde siparişin <i>tüm</i> kalemleri yer alır:
     * <ul>
     *   <li>O mutfağın hazırlayacağı kalemler → vurgulu (kalın, büyük font).</li>
     *   <li>Diğer mutfağa ait kalemler → küçük "(bilgi)" satırı.</li>
     * </ul>
     * Bu sayede her mutfak müşterinin tüm siparişini görüp koordine olabilir.
     *
     * @return  raporlanmak üzere her hedef için sonuç (başarılı/başarısız).
     */
    public List<PrintResult> sendOrderToKitchens(long orderId,
                                                 String salonName,
                                                 String tableNo,
                                                 String waiterName,
                                                 String orderNote,
                                                 List<OrderItem> items) {
        Map<KitchenPrinter, List<OrderItem>> grouped = router.routeItems(items);
        if (grouped.isEmpty()) {
            LOG.warn("Sipariş {} için hiçbir yazıcı yönlendirmesi bulunamadı", orderId);
            return List.of();
        }

        List<PrintResult> results = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<KitchenPrinter, List<OrderItem>> entry : grouped.entrySet()) {
            KitchenPrinter target = entry.getKey();
            List<OrderItem> myItems = entry.getValue();

            // O mutfağın yapacağı productId kümesi → hızlı kontrol
            Set<Long> mineProductIds = new HashSet<>();
            for (OrderItem it : myItems) {
                if (it.getProductId() != null) mineProductIds.add(it.getProductId());
            }

            // Tüm siparişin kalemlerini yaz; ama sadece bu mutfağa ait olanları "highlighted=true"
            List<Receipt.Line> lines = new ArrayList<>(items.size());
            for (OrderItem it : items) {
                boolean mine = it.getProductId() != null && mineProductIds.contains(it.getProductId());
                lines.add(new Receipt.Line(
                        it.getQuantity(),
                        it.getProductName(),
                        it.getNote(),
                        mine
                ));
            }

            Receipt receipt = new Receipt(
                    "*** " + safe(target.getDisplayName()).toUpperCase() + " ***",
                    salonName,
                    tableNo,
                    waiterName,
                    now,
                    lines,
                    orderNote,
                    orderId
            );

            PrintResult result = printOne(target, receipt);
            results.add(result);
        }
        return results;
    }

    private PrintResult printOne(KitchenPrinter target, Receipt receipt) {
        ReceiptPrinter printer = resolvePrinter(target);
        Long jobId = null;
        try {
            jobId = enqueueJob(target, receipt);          // önce kuyruğa düş (idempotency)
            printer.print(receipt);
            printJobDAO.markPrinted(jobId);
            return PrintResult.ok(target, jobId);
        } catch (PrinterException e) {
            LOG.error("Yazıcı hatası: {}", target, e);
            return handlePrintFailure(target, jobId, e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Beklenmeyen hata: {}", target, e);
            return handlePrintFailure(target, jobId, e.getMessage());
        }
    }

    private PrintResult handlePrintFailure(KitchenPrinter target, Long jobId, String message) {
        if (jobId != null) printJobDAO.markFailed(jobId, message);
        return PrintResult.fail(target, jobId, message);
    }

    private Long enqueueJob(KitchenPrinter target, Receipt receipt) {
        PrintJob job = new PrintJob();
        job.setOrderId(receipt.getOrderId());
        job.setPrinterId(printerId(target));
        job.setPayload(GSON.toJson(receipt));
        return printJobDAO.enqueue(job);
    }

    private ReceiptPrinter resolvePrinter(KitchenPrinter target) {
        if (printerOverride != null) {
            ReceiptPrinter override = printerOverride.get(printerId(target));
            if (override != null) return override;
        }
        int id = printerId(target);
        return printerCache.computeIfAbsent(id, k -> new TcpEscPosPrinter(
                target.getCode(),
                target.getDisplayName(),
                target.getHost(),
                target.getPort(),
                target.getCharPerLine(),
                3000,
                5000
        ));
    }

    /** Önbelleği boşaltır (yazıcı IP'si değiştirildiyse Admin panelden tetiklenir). */
    public void invalidateCache() {
        printerCache.clear();
    }

    private static int printerId(KitchenPrinter target) {
        return Math.toIntExact(target.getId());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ---- sonuç tipi ----
    public static final class PrintResult {
        public final KitchenPrinter target;
        public final Long jobId;
        public final boolean success;
        public final String errorMessage;

        private PrintResult(KitchenPrinter t, Long jobId, boolean ok, String err) {
            this.target = t;
            this.jobId = jobId;
            this.success = ok;
            this.errorMessage = err;
        }

        public static PrintResult ok(KitchenPrinter t, Long jobId) {
            return new PrintResult(t, jobId, true, null);
        }

        public static PrintResult fail(KitchenPrinter t, Long jobId, String err) {
            return new PrintResult(t, jobId, false, err);
        }

        @Override
        public String toString() {
            return (success ? "OK" : "FAIL") + " " + target + (errorMessage != null ? " - " + errorMessage : "");
        }
    }
}
