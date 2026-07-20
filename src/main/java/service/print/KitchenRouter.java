package service.print;

import dao.CategoryPrinterRouteDAO;
import dao.KitchenPrinterDAO;
import dao.ProductDAO;
import dao.jdbc.CategoryPrinterRouteJdbcDAO;
import dao.jdbc.KitchenPrinterJdbcDAO;
import dao.jdbc.ProductJdbcDAO;
import model.KitchenPrinter;
import model.OrderItem;
import model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bir siparişin kalemlerini ürün kategorilerine göre gruplayıp,
 * her kategori için hangi mutfak yazıcısına (veya yazıcılarına) düşmesi
 * gerektiğini belirler.
 *
 * <p>Yöntem:
 * <pre>
 *   sipariş kalemleri → ürün → kategori → category_printer_routes → KitchenPrinter[]
 * </pre>
 *
 * <p>Çoklu yönlendirme destekli: aynı kategori birden fazla yazıcıya
 * düşebilir (örn. hem hazırlık hem servis için).
 */
public class KitchenRouter {

    private static final Logger LOG = LoggerFactory.getLogger(KitchenRouter.class);

    private final KitchenPrinterDAO printerDAO;
    private final CategoryPrinterRouteDAO routeDAO;
    private final ProductDAO productDAO;

    public KitchenRouter() {
        this(new KitchenPrinterJdbcDAO(), new CategoryPrinterRouteJdbcDAO(), new ProductJdbcDAO());
    }

    public KitchenRouter(KitchenPrinterDAO printerDAO,
                         CategoryPrinterRouteDAO routeDAO,
                         ProductDAO productDAO) {
        this.printerDAO = printerDAO;
        this.routeDAO = routeDAO;
        this.productDAO = productDAO;
    }

    /**
     * Verilen sipariş kalemlerini yazıcı bazında gruplar.
     *
     * @return  yazıcı → o yazıcıya basılacak kalemler (LinkedHashMap — sıra korunur)
     */
    public Map<KitchenPrinter, List<OrderItem>> routeItems(List<OrderItem> items) {
        Map<KitchenPrinter, List<OrderItem>> grouped = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) return grouped;

        // Cache'ler: aynı kategori/yazıcı tekrar tekrar sorulmasın
        Map<Long, List<KitchenPrinter>> catCache = new HashMap<>();
        Map<Integer, KitchenPrinter> printerCache = new HashMap<>();

        for (OrderItem item : items) {
            // 1) ÖNCELİK: Garson manuel mutfak seçtiyse onu kullan.
            if (item.getKitchenOverrideId() != null) {
                Integer pid = item.getKitchenOverrideId();
                KitchenPrinter pr = printerCache.computeIfAbsent(pid,
                        k -> printerDAO.findById(k).orElse(null));
                if (pr != null && pr.isActive()) {
                    grouped.computeIfAbsent(pr, k -> new ArrayList<>()).add(item);
                    continue;
                }
                LOG.warn("Override yazıcı {} pasif/bulunamadı; kategori default'una düşülüyor", pid);
            }

            // 2) Aksi halde kategori → varsayılan yazıcı(lar)
            Long categoryId = resolveCategoryId(item);
            if (categoryId == null) {
                LOG.warn("Kalem categoryId çözülemedi, mutfağa düşürülmedi: item={}", item);
                continue;
            }

            List<KitchenPrinter> targets = catCache.computeIfAbsent(categoryId, cid -> {
                List<Integer> ids = routeDAO.findPrinterIdsByCategory(cid);
                List<KitchenPrinter> printers = new ArrayList<>(ids.size());
                for (Integer pid : ids) {
                    KitchenPrinter pr = printerCache.computeIfAbsent(pid,
                            k -> printerDAO.findById(k).orElse(null));
                    if (pr != null && pr.isActive()) printers.add(pr);
                }
                return printers;
            });

            if (targets.isEmpty()) {
                LOG.warn("Kategori {} için aktif yazıcı yok, kalem atlandı: item={}", categoryId, item);
                continue;
            }

            for (KitchenPrinter p : targets) {
                grouped.computeIfAbsent(p, k -> new ArrayList<>()).add(item);
            }
        }
        return grouped;
    }

    /** Order kaleminden kategori ID'sini çözer (Product tablosu üzerinden). */
    private Long resolveCategoryId(OrderItem item) {
        if (item.getProductId() == null) return null;
        Optional<Product> prod = productDAO.findById(item.getProductId());
        return prod.map(Product::getCategoryId).orElse(null);
    }

    /** Tek yönlendirme yokken kullanılacak boş sonuç. */
    public static Map<KitchenPrinter, List<OrderItem>> empty() {
        return Collections.emptyMap();
    }
}
