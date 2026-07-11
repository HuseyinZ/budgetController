package state;

import model.Category;
import model.Expense;
import model.ItemAddWithNoteResult;
import model.ItemNoteUpdateResult;
import model.Order;
import model.OrderItem;
import model.OrderStatus;
import model.Payment;
import model.PaymentMethod;
import model.Product;
import model.ProductSalesRow;
import model.RestaurantTable;
import model.TableStatus;
import model.User;
import service.CategoryService;
import service.ExpenseService;
import service.OrderLogService;
import service.OrderService;
import service.PaymentService;
import service.ProductService;
import service.ReportsService;
import service.RestaurantTableService;
import service.UserService;
import service.print.PrintingService;
import dao.UserAreaPermissionDAO;
import dao.jdbc.UserAreaPermissionJdbcDAO;
import dao.KitchenPrinterDAO;
import dao.CategoryPrinterRouteDAO;
import dao.jdbc.KitchenPrinterJdbcDAO;
import dao.jdbc.CategoryPrinterRouteJdbcDAO;
import model.KitchenPrinter;
import model.RefundLog;
import model.Role;
import model.UserAreaPermission;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.Locale;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class AppState {

    private static final Logger LOG = LoggerFactory.getLogger(AppState.class);

    public static final String EVENT_TABLES = "tables";
    public static final String EVENT_SALES = "sales";
    public static final String EVENT_EXPENSES = "expenses";
    public static final String EVENT_PRODUCTS = "products";
    public static final String EVENT_CATEGORIES = "categories";
    private static final int HISTORY_LIMIT = 50;
    private static final String DEFAULT_CATEGORY_NAME = "Genel";

    /**
     * Restoran düzeni: Bina → Kat → Salon → Masa hiyerarşisi.
     *
     * <p><b>Geriye uyumluluk:</b> Eski 3 seviyeli yapı (Bina + Section + Masa)
     * için {@code salon} parametresiz constructor kullanılabilir; bu durumda
     * {@link #getSalon()} boş döner ve UI tek seviyeli (sadece kat → masa) gösterir.
     */
    public static final class AreaDefinition {
        private final String building;
        private final String section;   // "Kat" anlamına gelir (örn. "1. Kat", "Bahçe")
        private final String salon;     // Opsiyonel — kat içindeki alt salon (örn. "1. Salon")
        private final int startTableNo;
        private final int tableCount;

        /** 3 seviyeli (geriye uyum): Bina + Kat + Masa. Salon boş. */
        public AreaDefinition(String building, String section, int startTableNo, int tableCount) {
            this(building, section, "", startTableNo, tableCount);
        }

        /** 4 seviyeli: Bina + Kat + Salon + Masa. */
        public AreaDefinition(String building, String section, String salon, int startTableNo, int tableCount) {
            this.building = building == null ? "" : building;
            this.section = section == null ? "" : section;
            this.salon = salon == null ? "" : salon;
            this.startTableNo = startTableNo;
            this.tableCount = tableCount;
        }

        public String getBuilding() {
            return building;
        }

        /** "Kat" karşılığı. UI'da bu seviye üst sıradaki butonlardır. */
        public String getSection() {
            return section;
        }

        /** Opsiyonel salon adı (örn. "1. Salon"). Boş string ise kat tek salonlu sayılır. */
        public String getSalon() {
            return salon;
        }

        public boolean hasSalon() {
            return salon != null && !salon.isBlank();
        }

        public List<Integer> getTableNumbers() {
            return java.util.stream.IntStream.range(0, tableCount)
                    .map(i -> startTableNo + i)
                    .boxed()
                    .collect(Collectors.toList());
        }
    }

    private static class Holder {
        private static final AppState INSTANCE = new AppState();
    }

    public static @NotNull AppState getInstance() {
        return Holder.INSTANCE;
    }

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private final List<AreaDefinition> areas;

    private final RestaurantTableService tableService;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final ProductService productService;
    private final CategoryService categoryService;
    private final UserService userService;
    private final ExpenseService expenseService;
    private final OrderLogService orderLogService;
    private final ReportsService reportsService;
    private final UserAreaPermissionDAO areaPermissionDAO = new UserAreaPermissionJdbcDAO();
    private final KitchenPrinterDAO kitchenPrinterDAO = new KitchenPrinterJdbcDAO();
    private final CategoryPrinterRouteDAO categoryRouteDAO = new CategoryPrinterRouteJdbcDAO();
    private final dao.RefundLogDAO refundLogDAO = new dao.jdbc.RefundLogJdbcDAO();

    private final Map<Integer, TableLayout> layouts = new LinkedHashMap<>();
    private final Map<Integer, Long> tableIds = new ConcurrentHashMap<>();
    private final Map<Integer, TableSignature> tableSignatures = new ConcurrentHashMap<>();
    private final Map<Long, Deque<OrderLogEntry>> orderHistories = new ConcurrentHashMap<>();
    private final Map<Integer, Long> tableOrderHistoryIndex = new ConcurrentHashMap<>();
    private final Map<Integer, Deque<OrderLogEntry>> tableHistories = new ConcurrentHashMap<>();
    private final AtomicReference<SalesSignature> salesSignature = new AtomicReference<>(SalesSignature.empty());
    private final AtomicReference<ExpensesSignature> expensesSignature = new AtomicReference<>(ExpensesSignature.empty());
    private final AtomicReference<Long> defaultCategoryId = new AtomicReference<>();
    private final Object categoryLock = new Object();
    private final ScheduledExecutorService poller;
    private boolean tableReserveUnsupported;

    private AppState() {
        this.tableService = new RestaurantTableService();
        this.orderService = new OrderService();
        this.paymentService = new PaymentService();
        this.categoryService = new CategoryService();
        this.productService = new ProductService(this.categoryService);
        this.userService = new UserService();
        this.expenseService = new ExpenseService();
        this.orderLogService = new OrderLogService();
        this.reportsService = new ReportsService();
        this.areas = createDefaultAreas();
        buildLayouts();
        initializeTables();
        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "app-state-poller");
            t.setDaemon(true);
            return t;
        });
        this.poller.scheduleAtFixedRate(this::pollChanges, 2, 2, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> poller.shutdownNow(), "app-state-poller-shutdown"));
    }

    /**
     * Masa düzenini yükler.
     *
     * <p>Yükleme öncelik sırası:
     * <ol>
     *   <li><code>~/.budget/restaurant-layout.properties</code>  (kullanıcı override'ı)</li>
     *   <li>Classpath: <code>/restaurant-layout.properties</code> (JAR içindeki varsayılan)</li>
     *   <li>Hardcoded fallback (her iki dosya da yoksa)</li>
     * </ol>
     *
     * <p>Restoran sahibi {@code restaurant-layout.properties} dosyasını
     * düzenleyerek bina/salon/masa numaralarını değiştirebilir. Format
     * için dosyanın başındaki yorumlara bakın.
     */
    private List<AreaDefinition> createDefaultAreas() {
        java.util.Properties props = new java.util.Properties();
        boolean loaded = false;

        // 1) Kullanıcı override'ı
        java.nio.file.Path userFile = java.nio.file.Path.of(
                System.getProperty("user.home"), ".budget", "restaurant-layout.properties");
        if (java.nio.file.Files.exists(userFile)) {
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(userFile)) {
                props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                loaded = true;
                LOG.info("Masa düzeni yüklendi: {}", userFile);
            } catch (java.io.IOException ex) {
                LOG.warn("restaurant-layout.properties okunamadı: " + ex.getMessage());
            }
        }

        // 2) Classpath default
        if (!loaded) {
            try (java.io.InputStream in =
                         AppState.class.getResourceAsStream("/restaurant-layout.properties")) {
                if (in != null) {
                    props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                    loaded = true;
                }
            } catch (java.io.IOException ex) {
                LOG.warn("Classpath layout okunamadı: " + ex.getMessage());
            }
        }

        // 3) Hardcoded fallback (her ikisi de yoksa)
        if (!loaded || props.isEmpty()) {
            LOG.warn("UYARI: restaurant-layout.properties bulunamadı; varsayılan kullanılacak.");
            List<AreaDefinition> fallback = new ArrayList<>();
            // 1. Bina — 3 kat, her katta 2 salon (5'er masa)
            fallback.add(new AreaDefinition("1. Bina", "1. Kat", "1. Salon", 101, 5));
            fallback.add(new AreaDefinition("1. Bina", "1. Kat", "2. Salon", 106, 5));
            fallback.add(new AreaDefinition("1. Bina", "2. Kat", "1. Salon", 111, 5));
            fallback.add(new AreaDefinition("1. Bina", "2. Kat", "2. Salon", 116, 5));
            fallback.add(new AreaDefinition("1. Bina", "3. Kat", "1. Salon", 121, 5));
            fallback.add(new AreaDefinition("1. Bina", "3. Kat", "2. Salon", 126, 5));
            // 2. Bina — 3 kat, her katta 2 salon
            fallback.add(new AreaDefinition("2. Bina", "1. Kat", "1. Salon", 201, 5));
            fallback.add(new AreaDefinition("2. Bina", "1. Kat", "2. Salon", 206, 5));
            fallback.add(new AreaDefinition("2. Bina", "2. Kat", "1. Salon", 211, 5));
            fallback.add(new AreaDefinition("2. Bina", "2. Kat", "2. Salon", 216, 5));
            fallback.add(new AreaDefinition("2. Bina", "3. Kat", "1. Salon", 221, 5));
            fallback.add(new AreaDefinition("2. Bina", "3. Kat", "2. Salon", 226, 5));
            // 3. Bina — açık alan, tek katlı, tek salonlu
            fallback.add(new AreaDefinition("3. Bina", "Bahçe",  "",         301, 10));
            return Collections.unmodifiableList(fallback);
        }

        return Collections.unmodifiableList(parseAreas(props));
    }

    /** Properties → AreaDefinition listesi. area.<N>.* anahtarları sıralı okunur. */
    private List<AreaDefinition> parseAreas(java.util.Properties props) {
        // Önce hangi N indeks numaralarının var olduğunu çıkar
        java.util.SortedSet<Integer> indexes = new java.util.TreeSet<>();
        for (Object key : props.keySet()) {
            String k = key.toString();
            if (!k.startsWith("area.")) continue;
            int firstDot = k.indexOf('.');
            int secondDot = k.indexOf('.', firstDot + 1);
            if (secondDot < 0) continue;
            String numStr = k.substring(firstDot + 1, secondDot);
            try {
                indexes.add(Integer.parseInt(numStr));
            } catch (NumberFormatException ignore) {}
        }

        List<AreaDefinition> defs = new ArrayList<>();
        for (Integer idx : indexes) {
            String prefix = "area." + idx + ".";
            String building = trim(props.getProperty(prefix + "building"));
            // Eski sürüm "section", yeni sürüm "floor" — ikisini de destekle (floor öncelikli)
            String section  = trim(props.getProperty(prefix + "floor"));
            if (section.isEmpty()) {
                section = trim(props.getProperty(prefix + "section"));
            }
            String salon    = trim(props.getProperty(prefix + "salon"));
            String startStr = trim(props.getProperty(prefix + "startTableNo"));
            String countStr = trim(props.getProperty(prefix + "tableCount"));
            if (building.isEmpty() || section.isEmpty() || startStr.isEmpty() || countStr.isEmpty()) {
                LOG.warn("UYARI: area." + idx + " eksik alan, atlanıyor.");
                continue;
            }
            int startTableNo;
            int tableCount;
            try {
                startTableNo = Integer.parseInt(startStr);
                tableCount = Integer.parseInt(countStr);
            } catch (NumberFormatException ex) {
                LOG.warn("UYARI: area." + idx + " sayısal hata, atlanıyor: " + ex.getMessage());
                continue;
            }
            if (tableCount <= 0 || startTableNo <= 0) {
                LOG.warn("UYARI: area." + idx + " geçersiz değerler, atlanıyor.");
                continue;
            }
            defs.add(new AreaDefinition(building, section, salon, startTableNo, tableCount));
        }
        if (defs.isEmpty()) {
            LOG.warn("UYARI: restaurant-layout.properties'de geçerli area yok.");
        }
        return defs;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private void buildLayouts() {
        for (AreaDefinition area : areas) {
            for (Integer tableNo : area.getTableNumbers()) {
                layouts.put(tableNo, new TableLayout(tableNo, area.getBuilding(), area.getSection()));
            }
        }
    }

    private void initializeTables() {
        for (Integer tableNo : layouts.keySet()) {
            try {
                ensureTableExists(tableNo);
            } catch (RuntimeException ex) {
                LOG.warn("Masa senkronizasyonu başarısız: " + tableNo + " - " + ex.getMessage());
            }
        }
    }

    public List<AreaDefinition> getAreas() {
        return areas;
    }

    public synchronized List<Product> getAvailableProducts() {
        return filterAndSortProducts(productService.getAllProducts());
    }

    /**
     * Tüm ürünleri (pasif/tükendi dahil) döner. Admin/Ürünler paneli ve
     * Garson menüsü (ProductPicker) bunu kullanır — pasifler gri görünüp
     * sipariş edilemese de ekranda yer alır.
     */
    public synchronized List<Product> getAllProductsIncludingInactive() {
        return filterAndSortProductsAll(productService.getAllProducts());
    }

    public synchronized List<Product> getProductsByCategoryName(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return getAvailableProducts();
        }
        return filterAndSortProducts(productService.getProductsByCategoryName(categoryName));
    }

    /** Kategoriye göre tüm ürünler (pasif dahil) — ProductPicker için. */
    public synchronized List<Product> getProductsByCategoryNameIncludingInactive(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return getAllProductsIncludingInactive();
        }
        return filterAndSortProductsAll(productService.getProductsByCategoryName(categoryName));
    }

    public synchronized Long createProduct(Product product) {
        Long id = productService.createProduct(product);
        notifyProductsChanged();
        return id;
    }

    public synchronized void updateProduct(Product product) {
        productService.updateProduct(product);
        notifyProductsChanged();
    }

    public synchronized void deleteProduct(Long productId) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("Geçersiz ürün ID");
        }
        productService.deleteProduct(productId);
        notifyProductsChanged();
    }

    /**
     * Bir ürünün "tükendi/stokta" durumunu değiştirir.
     * <p>Pasif (active=false) ürünler garson menüsünde gri/disabled görünür
     * ve sipariş edilemez. Admin/Aşçı menüden tek tıkla "tükendi" diyebilir.
     */
    public synchronized void setProductActive(Long productId, boolean active) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("Geçersiz ürün ID");
        }
        Product product = productService.getProductById(productId);
        if (product == null) {
            throw new IllegalArgumentException("Ürün bulunamadı: " + productId);
        }
        product.setActive(active);
        productService.updateProduct(product);
        notifyProductsChanged();
    }

    public synchronized List<Category> getAllCategories() {
        return categoryService.getAllCategories();
    }

    // ============================================================
    //   Kategori → Mutfak Yazıcısı Eşleştirme
    // ============================================================

    /** Tüm aktif mutfak yazıcılarını listeler (UI'da checkbox satırları için). */
    public List<KitchenPrinter> getAllKitchenPrinters() {
        return kitchenPrinterDAO.findActive();
    }

    /** Bir kategori için eşleştirilmiş yazıcı id'lerini döner. */
    public List<Integer> getPrinterIdsForCategory(Long categoryId) {
        if (categoryId == null) return List.of();
        return categoryRouteDAO.findPrinterIdsByCategory(categoryId);
    }

    /**
     * Bir kategorinin yazıcı atamalarını yeniler. Eski tüm atamalar silinir,
     * verilen yazıcı id'lerinin tümü eklenir.
     */
    public synchronized void replaceCategoryRoutes(Long categoryId, java.util.Set<Integer> printerIds) {
        if (categoryId == null) {
            throw new IllegalArgumentException("Kategori boş olamaz");
        }
        categoryRouteDAO.deleteByCategory(categoryId);
        if (printerIds == null || printerIds.isEmpty()) return;
        for (Integer pid : printerIds) {
            if (pid == null || pid <= 0) continue;
            categoryRouteDAO.link(categoryId, pid);
        }
    }

    // ============================================================
    //   Garson Alan Yetkilendirmesi (V2026_05_17c)
    // ============================================================

    /**
     * Tüm tanımlı alanlar (bina + salon eşsiz çiftleri) — yetkilendirme
     * UI'sında checkbox listesi oluşturmak için.
     */
    public List<AreaDefinition> getAllAreas() {
        return new ArrayList<>(areas);
    }

    /**
     * Bir kullanıcının erişebileceği alanları döner.
     * <ul>
     *   <li>ADMIN veya KASIYER → tüm areas (filtresiz).</li>
     *   <li>GARSON → user_area_permissions tablosundaki çiftler.</li>
     *   <li>İzin atanmamış garson → boş liste (hiçbir kat görmez — admin atayana kadar).</li>
     * </ul>
     */
    public List<AreaDefinition> getAccessibleAreas(User user) {
        if (user == null) return List.of();
        Role role = user.getRole();
        if (role == Role.ADMIN || role == Role.KASIYER) {
            return new ArrayList<>(areas);
        }
        // GARSON
        java.util.Set<String> allowed = getAccessibleAreaKeys(user);
        if (allowed.isEmpty()) return List.of();
        List<AreaDefinition> out = new ArrayList<>();
        for (AreaDefinition a : areas) {
            if (allowed.contains(areaKey(a.getBuilding(), a.getSection()))) {
                out.add(a);
            }
        }
        return out;
    }

    /** Erişim anahtarı seti (UI tablo filtresi için hızlı kontrol). */
    public java.util.Set<String> getAccessibleAreaKeys(User user) {
        if (user == null) return java.util.Set.of();
        Role role = user.getRole();
        if (role == Role.ADMIN || role == Role.KASIYER) {
            java.util.Set<String> all = new java.util.HashSet<>();
            for (AreaDefinition a : areas) {
                all.add(areaKey(a.getBuilding(), a.getSection()));
            }
            return all;
        }
        // GARSON — DB'den oku
        List<UserAreaPermission> perms = areaPermissionDAO.findByUserId(user.getId());
        java.util.Set<String> out = new java.util.HashSet<>();
        for (UserAreaPermission p : perms) {
            out.add(areaKey(p.getBuilding(), p.getSection()));
        }
        return out;
    }

    /** UI: Belirli garsonun mevcut yetkilerini döner (admin paneli için). */
    public List<UserAreaPermission> getPermissionsFor(Long userId) {
        if (userId == null) return List.of();
        return areaPermissionDAO.findByUserId(userId);
    }

    /**
     * Bir garsonun yetkilerini yeniler. Önce tüm eskileri siler, sonra
     * verilen anahtarları ekler. Anahtar format: {@code "Bina||Salon"}.
     */
    public synchronized void replaceAreaPermissions(Long userId, java.util.Set<String> areaKeys) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("Geçersiz userId");
        }
        areaPermissionDAO.deleteAllForUser(userId);
        if (areaKeys == null || areaKeys.isEmpty()) return;
        for (String key : areaKeys) {
            if (key == null) continue;
            int idx = key.indexOf("||");
            if (idx < 0) continue;
            String building = key.substring(0, idx);
            String section  = key.substring(idx + 2);
            areaPermissionDAO.grant(userId, building, section);
        }
        notifyTableChanged(-1); // tüm masalar yeniden değerlendirilsin
    }

    /** Salt iç kullanım — area anahtarı üretir. */
    public static String areaKey(String building, String section) {
        return (building == null ? "" : building.trim()) + "||"
                + (section == null ? "" : section.trim());
    }

    /** Bir masaya (tableNo) bu kullanıcının erişebilip erişemediğini kontrol eder. */
    public boolean canAccessTable(int tableNo, User user) {
        if (user == null) return false;
        Role role = user.getRole();
        if (role == Role.ADMIN || role == Role.KASIYER) return true;
        TableLayout layout = layouts.get(tableNo);
        if (layout == null) return false;
        java.util.Set<String> allowed = getAccessibleAreaKeys(user);
        return allowed.contains(areaKey(layout.building(), layout.section()));
    }

    public void addPropertyChangeListener(PropertyChangeListener listener) {
        pcs.addPropertyChangeListener(listener);
    }

    public void removePropertyChangeListener(PropertyChangeListener listener) {
        pcs.removePropertyChangeListener(listener);
    }

    public synchronized TableSnapshot snapshot(int tableNo) {
        TableLayout layout = requireLayout(tableNo);
        Long tableId = ensureTableExists(tableNo);
        Optional<Order> optOrder = orderService.getOpenOrderByTable(tableId);

        TableOrderStatus status = TableOrderStatus.EMPTY;
        List<OrderLine> lines = List.of();
        List<OrderLogEntry> history = List.of();
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        if (optOrder.isPresent()) {
            Order order = optOrder.get();
            List<OrderItem> items = orderService.getItemsForOrder(order.getId());
            lines = items.stream()
                    .map(this::toOrderLine)
                    .collect(Collectors.toUnmodifiableList());
            total = lines.stream()
                    .map(OrderLine::getLineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            status = mapOrderStatus(order.getStatus());
            List<OrderLogEntry> persisted = orderLogService.getRecentLogs(order.getId(), HISTORY_LIMIT);
            history = resolveHistorySnapshot(tableNo, persisted);
        } else {
            TableStatus tableStatus = tableService.getByTableNo(tableNo)
                    .map(RestaurantTable::getStatus)
                    .orElse(TableStatus.EMPTY);
            status = mapTableStatus(tableStatus);
            history = resolveHistorySnapshot(tableNo, List.of());
        }

        return new TableSnapshot(tableNo, layout.building(), layout.section(), status, lines, history, total);
    }

    public synchronized BigDecimal getTableTotal(int tableNo) {
        return snapshot(tableNo).getTotal();
    }

    public synchronized TableOrderStatus getTableStatus(int tableNo) {
        return snapshot(tableNo).getStatus();
    }

    // ============================================================
    //   Sipariş İşlemleri (ürün ekle / azalt / sil / temizle)
    // ============================================================

    public synchronized void addItem(int tableNo, Long productId, int quantity, User user) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("Geçersiz ürün");
        }
        Product product = productService.getProductById(productId);
        if (product == null) {
            throw new IllegalArgumentException("Ürün bulunamadı: " + productId);
        }
        addItemInternal(tableNo, product, quantity, user);
    }

    public synchronized void addItem(int tableNo, String productName, BigDecimal price, int quantity, User user) {
        Product product = ensureProduct(productName, price);
        addItemInternal(tableNo, product, quantity, user);
    }

    /**
     * Şiş/birim bazlı ekleme: garson "5 şiş ciğer" diye seçtiğinde kullanılır.
     * <p>Ürün şiş bazlı (piecesPerPortion>0) değilse {@link #addItem(int, Long, int, User)}
     * davranışına döner.
     *
     * @param pieces toplam şiş/birim sayısı (örn. 5)
     */
    public synchronized void addItemByPieces(int tableNo, Long productId, int pieces, User user) {
        if (productId == null || productId <= 0) {
            throw new IllegalArgumentException("Geçersiz ürün");
        }
        if (pieces <= 0) {
            throw new IllegalArgumentException("Şiş/birim sayısı 1 veya üzeri olmalı");
        }
        Product product = productService.getProductById(productId);
        if (product == null) {
            throw new IllegalArgumentException("Ürün bulunamadı: " + productId);
        }
        if (!product.isPieceBased()) {
            // Şiş bazlı değilse normal akış (pieces = quantity)
            addItemInternal(tableNo, product, pieces, user);
            return;
        }
        addItemInternalPieces(tableNo, product, pieces, user);
    }

    /** Not çakışma karşılaştırmasında Türkçe case-folding için (İ/ı doğru katlansın). */
    private static final Locale TR_NOTE_LOCALE = Locale.forLanguageTag("tr-TR");

    /**
     * Stage 0G — Pre-add not çakışması korumalı ekleme (orchestration wrapper).
     *
     * <p>Aynı ürün için mevcut satırın notu ile yeni notun normalize edilmiş halleri
     * farklıysa ürün HİÇ eklenmez ({@code itemAdded=false}): quantity artmaz,
     * history/orderLog yazılmaz, UI event yayınlanmaz. Çakışma yoksa mevcut
     * {@link #addItem(int, Long, int, User)} / {@link #addItemByPieces} ve not
     * istendiyse {@link #setItemNote} davranışları aynen kullanılır (intrinsic
     * monitor reentrant olduğundan iç çağrılar güvenlidir).
     *
     * <p>Guard + add + not tek synchronized blokta çalıştığı için in-process
     * yarışlara kapalıdır; DB-level tutarlılık garantisi DEĞİLDİR (Stage 0G
     * safety mitigation).
     *
     * @param pieces {@code null} → porsiyon bazlı ekleme ({@code quantity} kullanılır);
     *               non-null → mevcut şiş bazlı {@code addItemByPieces} yolu.
     */
    public synchronized ItemAddWithNoteResult addItemWithNote(int tableNo, Long productId,
                                                              int quantity, Integer pieces,
                                                              String note, User user) {
        // --- Guard: quantity artmadan ÖNCE not kimliği karşılaştırması ---
        if (productId != null && productId > 0) {
            Long tableId = ensureTableExists(tableNo);
            Order order = orderService.getOpenOrderByTable(tableId).orElse(null);
            if (order != null) {
                for (OrderItem item : orderService.getItemsForOrder(order.getId())) {
                    if (item != null && productId.equals(item.getProductId())) {
                        boolean notesDiffer = !normalizeNoteForCompare(item.getNote())
                                .equals(normalizeNoteForCompare(note));
                        // Capability YALNIZ potansiyel conflict anında doğrulanır.
                        // Doğrulanamadıysa (unsupported VEYA geçici hata) guard atlanır:
                        // note kolonu okunamayan şemada "note=null" görünümü gerçek bir
                        // çakışma değildir — davranışı B1/B2 zinciri belirler.
                        if (notesDiffer && orderService.isNoteColumnConfirmedAvailable()) {
                            return new ItemAddWithNoteResult(false, null);
                        }
                        break; // mevcut modelde order+product için tek satır varsayımı
                    }
                }
            }
        }
        // --- Çakışma yok: mevcut add davranışı aynen ---
        if (pieces != null) {
            addItemByPieces(tableNo, productId, pieces, user);
        } else {
            addItem(tableNo, productId, quantity, user);
        }
        // --- Not istendiyse mevcut setItemNote davranışı aynen ---
        ItemNoteUpdateResult noteResult = null;
        if (note != null && !note.isBlank()) {
            try {
                Product p = productService.getProductById(productId);
                String productName = (p == null) ? null : p.getName();
                noteResult = (productName == null)
                        ? ItemNoteUpdateResult.NOT_FOUND
                        : setItemNote(tableNo, productName, note, user);
            } catch (RuntimeException ex) {
                // Ürün eklendi; not aşaması hatası ekleme başarısını bozmamalı.
                // Güvenli log: yalnız exception sınıf adı (DB diagnostic/SQL metni sızmasın).
                LOG.warn(
                        "Item note application failed after add ({})",
                        ex.getClass().getSimpleName()
                );
                noteResult = ItemNoteUpdateResult.FAILED;
            }
        }
        return new ItemAddWithNoteResult(true, noteResult);
    }

    /**
     * Not çakışma karşılaştırması normalizasyonu: null→"", trim, ardışık
     * whitespace→tek boşluk, Türkçe locale lowercase. Token sort / virgül
     * parse YAPILMAZ (false-positive kabul, false-negative istenmiyor).
     */
    private static String normalizeNoteForCompare(String note) {
        if (note == null) return "";
        return note.trim().replaceAll("\\s+", " ").toLowerCase(TR_NOTE_LOCALE);
    }

    private void addItemInternalPieces(int tableNo, Product product, int pieces, User user) {
        Long productId = product.getId();
        if (productId == null || productId <= 0) {
            throw new IllegalStateException("Ürün kaydedilmemiş: " + safeProductName(product));
        }
        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId)
                .orElseGet(() -> orderService.createOrder(tableId, user == null ? null : user.getId()));
        // Stok yönetimi UI'dan kaldırıldı → virtual restock'a gerek yok.
        // OrderService overload'ı pieces parametresiyle çağrılır → şiş bazlı fiyat
        orderService.addItemToOrder(order.getId(), productId, pieces, pieces);
        orderService.updateOrderStatus(order.getId(), OrderStatus.IN_PROGRESS);
        orderService.recomputeTotals(order.getId());
        tableService.markTableOccupied(tableId, true);
        String label = product.getUnitLabel() == null ? "şiş" : product.getUnitLabel();
        String desc = pieces + " " + label + " " + safeProductName(product);
        recordHistory(tableNo, order.getId(), historyEntry(user, desc + " ekledi"));
        orderLogService.append(order.getId(), historyEntry(user, desc + " ekledi"));
        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
    }

    private void addItemInternal(int tableNo, Product product, int quantity, User user) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Adet sıfır olamaz");
        }
        if (product == null) {
            throw new IllegalArgumentException("Ürün bulunamadı");
        }
        Product resolved = product;
        Long productId = resolved.getId();
        if (productId == null || productId <= 0) {
            resolved = ensureProduct(resolved.getName(), resolved.getUnitPrice());
            productId = resolved.getId();
        }
        if (productId == null || productId <= 0) {
            throw new IllegalStateException("Ürün kaydedilemedi: " + safeProductName(resolved));
        }
        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId)
                .orElseGet(() -> orderService.createOrder(tableId, user == null ? null : user.getId()));
        // Stok yönetimi UI'dan kaldırıldı → virtual restock'a gerek yok.
        // OrderService.addItemToOrder içinde stok azaltması zaten yapılıyor;
        // CHECK constraint patlarsa ProductJdbcDAO.updateStock clamp ile geçer.
        orderService.addItemToOrder(order.getId(), productId, quantity);
        orderService.updateOrderStatus(order.getId(), OrderStatus.IN_PROGRESS);
        orderService.recomputeTotals(order.getId());
        tableService.markTableOccupied(tableId, true);
        String productLabel = safeProductName(resolved);
        if (productLabel.isEmpty()) {
            productLabel = "Ürün";
        }
        recordHistory(tableNo, order.getId(), historyEntry(user, quantity + " x " + productLabel + " ekledi"));
        orderLogService.append(order.getId(), historyEntry(user, quantity + " x " + productLabel + " ekledi"));
        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
    }

    public synchronized void decreaseItem(int tableNo, String productName, int quantity, User user) {
        decreaseItem(tableNo, productName, quantity, user, null);
    }

    /**
     * Bir kalemin adedini azaltır. Audit log için {@code reason} parametresi
     * alır — refund_log tablosuna yazılır.
     *
     * <p><b>Yetki:</b>
     * <ul>
     *   <li>Garson: SADECE 'pending' (henüz mutfağa gönderilmemiş) kalemi azaltabilir.</li>
     *   <li>Admin/Kasiyer: Her zaman izinli.</li>
     * </ul>
     *
     * @param reason iade nedeni — null/boş ise garson için OK, kalem pending ise
     *               opsiyonel; admin/kasiyer her durumda yazmaya zorlanmalı (UI'da kontrol).
     */
    public synchronized void decreaseItem(int tableNo, String productName, int quantity, User user, String reason) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Adet sıfır olamaz");
        }
        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId)
                .orElseThrow(() -> new IllegalArgumentException("Aktif sipariş bulunamadı: " + tableNo));
        OrderItem item = findOrderItem(order.getId(), productName);
        if (item == null) {
            return;
        }
        decreaseItemInternal(tableNo, tableId, order, item, productName, quantity, user, reason);
    }

    /**
     * Stage 1B (öne çekilen parça): kalemi {@code order_items.id} kimliğiyle azaltır.
     *
     * <p><b>Ownership:</b> {@code orderItemId} yalnız BU masanın açık siparişinin
     * kalemleri içinde aranır — başka masaya ait id hiçbir mutasyon yapmadan
     * {@code false} döner. {@link #ensureMayModifyItem} aynen uygulanır
     * (ownership onun yerine geçmez; yetkisizlikte SecurityException).
     *
     * @return {@code true} = mutasyon uygulandı; {@code false} = açık sipariş yok
     *         veya kalem bu siparişte bulunamadı (mutasyon yapılmadı).
     */
    public synchronized boolean decreaseItemById(int tableNo, long orderItemId, int quantity,
                                                 User user, String reason) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Adet sıfır olamaz");
        }
        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId).orElse(null);
        if (order == null) {
            return false;
        }
        OrderItem item = findOrderItemById(order.getId(), orderItemId);
        if (item == null) {
            return false; // bu masanın açık siparişinde yok (cross-table id dahil) — mutasyon YOK
        }
        decreaseItemInternal(tableNo, tableId, order, item, displayName(item), quantity, user, reason);
        return true;
    }

    /**
     * Azaltmanın ortak gövdesi — name-based ve id-based yollar için birebir aynı
     * davranış (yetki, stok iadesi, totals, history/orderLog, refund audit,
     * boş sipariş kapatma, refresh+notify).
     */
    private void decreaseItemInternal(int tableNo, Long tableId, Order order, OrderItem item,
                                      String productLabel, int quantity, User user, String reason) {
        // Yetki kontrolü: garson sadece pending (mutfağa gönderilmemiş) kalemi azaltabilir
        ensureMayModifyItem(user, item, "kalem azalt");
        orderService.decrementItem(item.getId(), quantity);
        if (item.getProductId() != null) {
            productService.decreaseProductStock(item.getProductId(), quantity);
        }
        orderService.recomputeTotals(order.getId());
        recordHistory(tableNo, order.getId(), historyEntry(user, quantity + " x " + productLabel + " azalttı"));
        orderLogService.append(order.getId(), historyEntry(user, quantity + " x " + productLabel + " azalttı"));

        // Audit log — iade kaydı tut
        java.math.BigDecimal lineRefund = resolveUnitPrice(item)
                .multiply(java.math.BigDecimal.valueOf(quantity))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        writeRefundLog(user, RefundLog.ActionType.DECREASE_ITEM,
                tableNo, order.getId(), productLabel, quantity, lineRefund, reason);

        if (orderService.getItemsForOrder(order.getId()).isEmpty()) {
            // Tüm kalemler silindi → siparişi kapat ve masayı boşalt
            closeEmptyOrderAndFreeTable(tableNo, tableId, order);
        }
        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
    }

    public synchronized void removeItem(int tableNo, String productName, User user) {
        removeItem(tableNo, productName, user, null);
    }

    /**
     * Bir kalemi tamamen siler. Audit log için {@code reason} alır.
     *
     * <p><b>Yetki:</b>
     * <ul>
     *   <li>Garson: SADECE 'pending' (mutfağa gönderilmemiş) kalemi silebilir.</li>
     *   <li>Admin/Kasiyer: Her zaman izinli. UI'da reason girilmesi zorunlu.</li>
     * </ul>
     */
    public synchronized void removeItem(int tableNo, String productName, User user, String reason) {
        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId).orElse(null);
        if (order == null) {
            return;
        }
        OrderItem item = findOrderItem(order.getId(), productName);
        if (item == null) {
            return;
        }
        removeItemInternal(tableNo, tableId, order, item, productName, user, reason);
    }

    /**
     * Stage 1B (öne çekilen parça): kalemi {@code order_items.id} kimliğiyle tamamen siler.
     *
     * <p><b>Ownership:</b> {@code orderItemId} yalnız BU masanın açık siparişinin
     * kalemleri içinde aranır — başka masaya ait id hiçbir mutasyon yapmadan
     * {@code false} döner. {@link #ensureMayModifyItem} aynen uygulanır.
     *
     * @return {@code true} = silindi; {@code false} = açık sipariş yok veya kalem
     *         bu siparişte bulunamadı (mutasyon yapılmadı).
     */
    public synchronized boolean removeItemById(int tableNo, long orderItemId, User user, String reason) {
        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId).orElse(null);
        if (order == null) {
            return false;
        }
        OrderItem item = findOrderItemById(order.getId(), orderItemId);
        if (item == null) {
            return false; // bu masanın açık siparişinde yok (cross-table id dahil) — mutasyon YOK
        }
        removeItemInternal(tableNo, tableId, order, item, displayName(item), user, reason);
        return true;
    }

    /**
     * Silmenin ortak gövdesi — name-based ve id-based yollar için birebir aynı davranış.
     */
    private void removeItemInternal(int tableNo, Long tableId, Order order, OrderItem item,
                                    String productLabel, User user, String reason) {
        // Yetki kontrolü
        ensureMayModifyItem(user, item, "kalem sil");
        int qty = item.getQuantity();
        orderService.decrementItem(item.getId(), qty);
        if (item.getProductId() != null && qty > 0) {
            productService.decreaseProductStock(item.getProductId(), qty);
        }
        orderService.recomputeTotals(order.getId());
        recordHistory(tableNo, order.getId(), historyEntry(user, productLabel + " ürününü sildi"));
        orderLogService.append(order.getId(), historyEntry(user, productLabel + " ürününü sildi"));

        // Audit log
        java.math.BigDecimal refundAmount = resolveUnitPrice(item)
                .multiply(java.math.BigDecimal.valueOf(Math.max(0, qty)))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        writeRefundLog(user, RefundLog.ActionType.REMOVE_ITEM,
                tableNo, order.getId(), productLabel, qty, refundAmount, reason);

        if (orderService.getItemsForOrder(order.getId()).isEmpty()) {
            // Son kalem de silindi → siparişi kapat, masayı boşalt
            closeEmptyOrderAndFreeTable(tableNo, tableId, order);
        }
        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
    }

    /** Kalemi {@code order_items.id} ile — yalnız verilen siparişin kalemleri içinde — bulur. */
    private OrderItem findOrderItemById(Long orderId, long orderItemId) {
        for (OrderItem item : orderService.getItemsForOrder(orderId)) {
            if (item != null && item.getId() != null && item.getId() == orderItemId) {
                return item;
            }
        }
        return null;
    }

    /** History/audit metinleri için ürün adı (id-based yol) — çözülemezse "Ürün". */
    private String displayName(OrderItem item) {
        String name = resolveProductName(item);
        return (name == null || name.isBlank()) ? "Ürün" : name;
    }

    // ============================================================
    //   Yetki & audit log yardımcıları
    // ============================================================

    /**
     * Bir kalemi değiştirme (azalt/sil) yetkisi kontrolü.
     * <p>Garson sadece pending (mutfağa gönderilmemiş) kalemi değiştirebilir.
     * Admin/Kasiyer her durumda izinli.
     *
     * @throws SecurityException yetkisiz erişimde
     */
    private void ensureMayModifyItem(User user, OrderItem item, String actionDesc) {
        if (user == null) {
            throw new SecurityException("Kullanıcı bilinmiyor, '" + actionDesc + "' işlemi yapılamaz");
        }
        Role role = user.getRole();
        if (role == Role.ADMIN || role == Role.KASIYER) {
            return; // serbest
        }
        // GARSON
        if (item == null || item.isPending()) {
            return; // pending kalem garson tarafından değiştirilebilir
        }
        throw new SecurityException(
                "Bu kalem zaten mutfağa gönderilmiş — '" + actionDesc + "' için Admin/Kasiyer onayı gerekir");
    }

    /** İade/iptal işlemleri için ADMIN veya KASIYER yetkisi şart. Garson YAPAMAZ. */
    private void ensureRefundPrivilege(User user, String actionDesc) {
        if (user == null) {
            throw new SecurityException("Kullanıcı bilinmiyor, '" + actionDesc + "' yetkisi yok");
        }
        Role role = user.getRole();
        if (role != Role.ADMIN && role != Role.KASIYER) {
            throw new SecurityException(
                    "'" + actionDesc + "' işlemi için Admin veya Kasiyer yetkisi gerekir");
        }
    }

    /**
     * refund_log tablosuna bir kayıt yazar — hatalar sessiz loglanır (asıl işlem
     * geri alınmaz).
     */
    private void writeRefundLog(User user, RefundLog.ActionType type, Integer tableNo, Long orderId,
                                String productName, Integer quantity,
                                java.math.BigDecimal amount, String reason) {
        try {
            RefundLog log = new RefundLog();
            log.setActionType(type);
            log.setUserId(user == null ? null : user.getId());
            log.setUserName(user == null ? null : (user.getFullName() != null && !user.getFullName().isBlank()
                    ? user.getFullName() : user.getUsername()));
            log.setTableNo(tableNo);
            log.setOrderId(orderId);
            log.setProductName(productName);
            log.setQuantity(quantity);
            log.setAmount(amount);
            log.setReason(reason);
            refundLogDAO.create(log);
        } catch (RuntimeException ex) {
            LOG.warn("Audit log yazılamadı ({}): {}", type, ex.getMessage());
        }
    }

    // ============================================================
    //   Masa transferi
    // ============================================================

    /**
     * Bir masadaki açık siparişi başka boş masaya taşır.
     *
     * <p>İş kuralları:
     * <ul>
     *   <li>Kaynak masada AÇIK bir sipariş olmalı (yoksa hata).</li>
     *   <li>Hedef masa BOŞ olmalı (açık siparişi varsa hata).</li>
     *   <li>Garson kullanıcı her iki masaya da erişim yetkisine sahip olmalı.</li>
     *   <li>Admin/Kasiyer her zaman izinli.</li>
     * </ul>
     */
    public synchronized void transferTable(int fromTableNo, int toTableNo, User user) {
        if (fromTableNo == toTableNo) {
            throw new IllegalArgumentException("Kaynak ve hedef masa aynı");
        }
        if (user == null) {
            throw new SecurityException("Kullanıcı bilinmiyor — masa transferi yapılamaz");
        }
        // Yetki kontrolü — garson hem kaynak hem hedef masaya erişebilmeli
        if (!canAccessTable(fromTableNo, user) || !canAccessTable(toTableNo, user)) {
            throw new SecurityException(
                    "Bu masalardan en az birinin yetkisi yok (Masa " + fromTableNo
                    + " → Masa " + toTableNo + ")");
        }

        Long fromTableId = ensureTableExists(fromTableNo);
        Long toTableId   = ensureTableExists(toTableNo);

        Order fromOrder = orderService.getOpenOrderByTable(fromTableId).orElse(null);
        if (fromOrder == null) {
            throw new IllegalArgumentException("Masa " + fromTableNo + " boş — taşınacak sipariş yok");
        }
        Order toOrder = orderService.getOpenOrderByTable(toTableId).orElse(null);
        if (toOrder != null) {
            throw new IllegalArgumentException("Masa " + toTableNo + " dolu — önce hedef masayı boşaltın");
        }

        // Order'ın table_id'sini değiştir
        orderService.reassignTable(fromOrder.getId(), toTableId);
        // Eski masayı boşalt, yenisini dolu yap
        try {
            tableService.markTableOccupied(fromTableId, false);
        } catch (RuntimeException e) {
            LOG.debug("Source table occupancy cleanup failed after transfer; ignored: {}", e.toString());
        }
        try {
            tableService.markTableOccupied(toTableId, true);
        } catch (RuntimeException e) {
            LOG.debug("Target table occupancy update failed after transfer; ignored: {}", e.toString());
        }

        String msg = "siparişi Masa " + fromTableNo + " → Masa " + toTableNo + " taşıdı";
        recordHistory(fromTableNo, fromOrder.getId(), historyEntry(user, msg));
        recordHistory(toTableNo, fromOrder.getId(), historyEntry(user, msg));
        orderLogService.append(fromOrder.getId(), historyEntry(user, msg));

        refreshTableSignature(fromTableNo);
        refreshTableSignature(toTableNo);
        notifyTableChanged(fromTableNo);
        notifyTableChanged(toTableNo);
    }

    /**
     * Garson/Admin için "boş hedef masa" listesini döner.
     * Kaynak masa hariç, kullanıcının erişebildiği ve şu an siparişsiz olan masalar.
     */
    public synchronized List<Integer> getAvailableTransferTargets(int fromTableNo, User user) {
        if (user == null) return List.of();
        List<Integer> result = new ArrayList<>();
        for (Integer tableNo : layouts.keySet()) {
            if (tableNo == fromTableNo) continue;
            if (!canAccessTable(tableNo, user)) continue;
            Long tableId = tableIds.get(tableNo);
            if (tableId == null) continue;
            if (orderService.getOpenOrderByTable(tableId).isPresent()) continue;
            result.add(tableNo);
        }
        Collections.sort(result);
        return result;
    }

    // ============================================================
    //   Masa kilidi (concurrent koruma)
    // ============================================================

    /**
     * Bir masaya kim girdi bilgisi. Aynı masaya iki garson aynı anda
     * girip ayrı kalemler eklerse veri karışır → bu lock ile önleriz.
     */
    public static final class TableLock {
        public final Long userId;
        public final String userName;
        public final long acquiredAt;
        public TableLock(Long userId, String userName, long acquiredAt) {
            this.userId = userId; this.userName = userName; this.acquiredAt = acquiredAt;
        }
    }

    /** Aktif masa kilitleri — in-memory. Restart sonrası temizlenir. */
    private final java.util.concurrent.ConcurrentHashMap<Integer, TableLock> tableLocks =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Lock TTL: bu süre boyunca yenileme gelmezse otomatik bırakılır. */
    private static final long LOCK_TTL_MILLIS = 120_000L;  // 2 dakika

    /**
     * Bir masayı kilitlemeye çalışır. Başka biri kilitliyse ve TTL dolmadıysa
     * false döner; başarılıysa true.
     *
     * <p>Aynı kullanıcı tekrar lock isterse refresh sayılır (acquiredAt güncellenir).
     */
    public boolean acquireTableLock(int tableNo, User user) {
        if (user == null) return false;
        long now = System.currentTimeMillis();
        TableLock existing = tableLocks.get(tableNo);
        if (existing != null) {
            // Süresi dolmuşsa kaldır
            if (now - existing.acquiredAt > LOCK_TTL_MILLIS) {
                tableLocks.remove(tableNo);
            } else if (java.util.Objects.equals(existing.userId, user.getId())) {
                // Aynı kullanıcı → refresh
                tableLocks.put(tableNo, new TableLock(user.getId(),
                        nameOf(user), now));
                return true;
            } else {
                // Başkası tutuyor
                return false;
            }
        }
        tableLocks.put(tableNo, new TableLock(user.getId(), nameOf(user), now));
        return true;
    }

    /** Kilidi bırakır (sadece sahibi). */
    public void releaseTableLock(int tableNo, User user) {
        if (user == null) return;
        tableLocks.computeIfPresent(tableNo, (no, lock) ->
                java.util.Objects.equals(lock.userId, user.getId()) ? null : lock);
    }

    /** Mevcut kilit bilgisi (null = kilit yok veya süresi dolmuş). */
    public TableLock getTableLock(int tableNo) {
        TableLock lock = tableLocks.get(tableNo);
        if (lock == null) return null;
        if (System.currentTimeMillis() - lock.acquiredAt > LOCK_TTL_MILLIS) {
            tableLocks.remove(tableNo);
            return null;
        }
        return lock;
    }

    private static String nameOf(User u) {
        if (u == null) return "?";
        String n = u.getFullName();
        if (n != null && !n.isBlank()) return n;
        return u.getUsername() == null ? "?" : u.getUsername();
    }

    /** İşlem geçmişi panelinde gösterilecek tüm refund kayıtları (en yeni üstte). */
    public synchronized List<RefundLog> getAllRefundLogs() {
        try {
            return refundLogDAO.findAll();
        } catch (RuntimeException ex) {
            LOG.warn("Refund log okunamadı: {}", ex.getMessage());
            return List.of();
        }
    }

    /** Tarih aralığına göre refund log filtresi. */
    public synchronized List<RefundLog> getRefundLogsByDateRange(LocalDate from, LocalDate to) {
        try {
            return refundLogDAO.findByDateRange(from, to);
        } catch (RuntimeException ex) {
            LOG.warn("Refund log okunamadı: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * Bir siparişte hiç kalem kalmadığında çağrılır. Order'ı CANCELLED yapar,
     * masa atamasını kaldırır ve dining_tables.status'u EMPTY yapar.
     */
    private void closeEmptyOrderAndFreeTable(int tableNo, Long tableId, Order order) {
        try {
            orderService.updateOrderStatus(order.getId(), OrderStatus.CANCELLED);
            orderService.reassignTable(order.getId(), null);
        } catch (RuntimeException e) {
            LOG.debug("Empty order cancel/reassign cleanup failed; ignored: {}", e.toString());
        }
        try {
            tableService.markTableOccupied(tableId, false);
        } catch (RuntimeException e) {
            LOG.debug("Empty order table occupancy cleanup failed; ignored: {}", e.toString());
        }
        recordHistory(tableNo, order.getId(),
                "Sipariş tamamen boşaltıldığı için masa boş duruma alındı");
    }

    /**
     * Bir kaleme not / özelleştirme atar.
     *
     * @param tableNo masa
     * @param productName kalemin ürün adı (snapshot)
     * @param note  boş string → notu temizle; null → işlem iptal
     */
    public synchronized ItemNoteUpdateResult setItemNote(int tableNo, String productName, String note, User user) {
        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId).orElse(null);
        if (order == null) return ItemNoteUpdateResult.NOT_FOUND;
        OrderItem item = findOrderItem(order.getId(), productName);
        if (item == null) return ItemNoteUpdateResult.NOT_FOUND;

        ItemNoteUpdateResult result = orderService.updateItemNote(item.getId(), note);
        if (result != ItemNoteUpdateResult.APPLIED) {
            // Not gerçekten uygulanmadı — history/orderLog yazma, UI event yayma.
            return result;
        }
        String summary = (note == null || note.isBlank())
                ? productName + " notu temizlendi"
                : productName + " notu: \"" + note + "\"";
        recordHistory(tableNo, order.getId(), historyEntry(user, summary));
        orderLogService.append(order.getId(), historyEntry(user, summary));
        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
        return ItemNoteUpdateResult.APPLIED;
    }

    public synchronized void clearTable(int tableNo, User user) {
        clearTable(tableNo, user, null);
    }

    /**
     * Masayı tamamen temizler (tüm kalemleri siler, siparişi iptal eder).
     *
     * <p><b>Yetki:</b> SADECE Admin veya Kasiyer. Garson çağıramaz.
     * <p>Audit log'a yazılır.
     *
     * @param reason iade nedeni — null/boş olabilir ama UI'da zorunlu tutulmalı.
     */
    public synchronized void clearTable(int tableNo, User user, String reason) {
        // Yetki kontrolü — garson masayı temizleyemez
        ensureRefundPrivilege(user, "masa temizle");

        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId).orElse(null);
        if (order == null) {
            tableService.markTableOccupied(tableId, false);
            refreshTableSignature(tableNo);
            notifyTableChanged(tableNo);
            return;
        }
        List<OrderItem> items = orderService.getItemsForOrder(order.getId());

        // Toplam iade tutarı (audit için)
        java.math.BigDecimal totalRefund = items.stream()
                .filter(i -> i != null && i.getQuantity() > 0)
                .map(this::lineTotal)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);

        orderService.clearItems(order.getId());
        for (OrderItem item : items) {
            if (item.getProductId() != null && item.getQuantity() > 0) {
                productService.decreaseProductStock(item.getProductId(), item.getQuantity());
            }
        }
        orderService.updateOrderStatus(order.getId(), OrderStatus.CANCELLED);
        orderService.reassignTable(order.getId(), null);
        // Masa durumunu EMPTY yap
        try {
            tableService.markTableOccupied(tableId, false);
        } catch (RuntimeException e) {
            LOG.debug("Table cleanup state sync failed; ignored: {}", e.toString());
        }
        recordHistory(tableNo, order.getId(), historyEntry(user, "masayı temizledi"));

        // Audit log
        writeRefundLog(user, RefundLog.ActionType.CLEAR_TABLE,
                tableNo, order.getId(), null, items.size(), totalRefund, reason);

        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
    }

    public synchronized void markServed(int tableNo, User user) {
        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId).orElse(null);
        if (order == null) {
            return;
        }
        orderService.updateOrderStatus(order.getId(), OrderStatus.READY);
        // "Sipariş hazır" denince bütün pending kalemleri "mutfakta" olarak işaretle
        // (kullanıcı zaten mutfağa gönderme adımını yapmış varsayılıyor)
        try {
            orderService.markAllItemsPrinted(order.getId());
        } catch (RuntimeException ignored) {
            // print_count sütunu yoksa sessiz geç
        }
        if (tableReserveUnsupported) {
            tableService.markTableOccupied(tableId, true);
        } else {
            try {
                tableService.markTableReserved(tableId);
            } catch (RuntimeException ex) {
                tableReserveUnsupported = true;
                LOG.warn("Masa durumu 'RESERVED' olarak işaretlenemedi. 'OCCUPIED' kullanılacak. Ayrıntı: "
                        + ex.getMessage());
                tableService.markTableOccupied(tableId, true);
            }
        }
        recordHistory(tableNo, order.getId(), historyEntry(user, "siparişi servis etti"));
        orderLogService.append(order.getId(), historyEntry(user, "siparişi servis etti"));
        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
    }

    /**
     * Açık siparişin tüm kalemlerini ilgili mutfak yazıcılarına gönderir.
     *
     * <p>Bu metod transaction içermez — yalnız ağ G/Ç yapar. Bu yüzden
     * UI thread'inde çağırmaktan kaçının (Swing'in dışında bir worker'da
     * tetikleyin) — örn. {@code SwingWorker} ile.
     *
     * @param tableNo     masa numarası (uygulamadaki visible number)
     * @param user        gönderimi yapan kullanıcı (log için)
     * @param printing    yazıcı servisi (null verilirse sessiz no-op)
     * @return  mutfak bazında baskı sonuçları (boş liste = yapılacak iş yok)
     */
    public List<PrintingService.PrintResult> sendOrderToKitchens(int tableNo,
                                                                 User user,
                                                                 PrintingService printing) {
        Long tableId = tableIds.get(tableNo);
        if (tableId == null) {
            return List.of();
        }
        Order open = orderService.getOpenOrderByTable(tableId).orElse(null);
        if (open == null) {
            return List.of();
        }
        TableLayout layout = layouts.get(tableNo);
        String building = layout == null ? "" : safeStr(layout.building());
        String section  = layout == null ? "" : safeStr(layout.section());
        String salonName;
        if (building.isBlank() && section.isBlank())      salonName = "";
        else if (building.isBlank())                       salonName = section;
        else if (section.isBlank())                        salonName = building;
        else                                               salonName = building + " / " + section;
        List<PrintingService.PrintResult> results =
                orderService.sendToKitchens(open.getId(), salonName, printing);

        // Log: her mutfak için bir kayıt
        for (PrintingService.PrintResult r : results) {
            String msg = "Mutfağa gönderildi (" + (r.target == null ? "?" : r.target.getDisplayName()) + ")"
                    + (r.success ? "" : " — HATA: " + r.errorMessage);
            recordHistory(tableNo, open.getId(), historyEntry(user, msg));
        }
        // Sesli bildirim — sadece "mutfağa sipariş geldi" tonu
        boolean anySuccess = results.stream().anyMatch(r -> r.success);
        if (anySuccess) {
            service.sound.SoundService.play(service.sound.SoundService.Event.KITCHEN_SENT);
        }
        notifyTableChanged(tableNo);
        return results;
    }

    private static String safeStr(String s) { return s == null ? "" : s; }

    // ============================================================
    //   Satış & Ödeme İşlemleri
    // ============================================================

    /**
     * Hesap bölme — birden fazla ödeme yöntemiyle aynı siparişi kapatır.
     *
     * <p>Her {@code SplitPart} ayrı bir Payment kaydı oluşturur (amount + method).
     * Toplamların satışın toplamına eşit olması beklenir; ufak farklar (kuruş
     * yuvarlamasından) tolere edilir.
     *
     * <p><b>Yetki:</b> ADMIN veya KASIYER. Garson çağıramaz.
     */
    public synchronized void recordSplitSale(int tableNo, User user, List<SplitPart> parts) {
        ensureRefundPrivilege(user, "hesap böl");
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("En az 1 ödeme parçası olmalı");
        }
        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId).orElse(null);
        if (order == null) {
            throw new IllegalArgumentException("Masa " + tableNo + " açık siparişi yok");
        }
        // Toplam tutarı hesapla — sipariş kalemlerinden
        List<OrderItem> items = orderService.getItemsForOrder(order.getId());
        java.math.BigDecimal expectedTotal = items.stream()
                .map(this::lineTotal)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);

        // Parça toplamı = sipariş toplamı kontrolü
        java.math.BigDecimal sumParts = parts.stream()
                .map(SplitPart::amount)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        if (sumParts.subtract(expectedTotal).abs()
                .compareTo(new java.math.BigDecimal("0.10")) > 0) {
            throw new IllegalArgumentException(
                    "Parça toplamları (" + sumParts + ") sipariş toplamına ("
                    + expectedTotal + ") eşit değil");
        }

        // Her parça için ayrı Payment kaydı
        Long cashierId = user.getId();
        for (SplitPart part : parts) {
            if (part == null || part.amount() == null || part.method() == null) continue;
            paymentService.recordPayment(order.getId(), cashierId, part.amount(), part.method());
        }
        // Siparişi kapat ve masayı boşalt
        orderService.updateOrderStatus(order.getId(), OrderStatus.COMPLETED);
        try {
            tableService.markTableOccupied(tableId, false);
        } catch (RuntimeException e) {
            LOG.debug("Split-payment table occupancy cleanup failed; ignored: {}", e.toString());
        }

        StringBuilder summary = new StringBuilder("hesabı ").append(parts.size())
                .append(" parça olarak böldü (toplam ").append(formatCurrency(expectedTotal)).append(")");
        recordHistory(tableNo, order.getId(), historyEntry(user, summary.toString()));
        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
        notifySalesChanged();
    }

    /** Tek hesap parçası — tutar + ödeme yöntemi. */
    public record SplitPart(java.math.BigDecimal amount, PaymentMethod method) {}

    public synchronized void recordSale(int tableNo, PaymentMethod method, User user) {
        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId).orElse(null);
        if (order == null) {
            return;
        }
        List<OrderItem> items = orderService.getItemsForOrder(order.getId());
        BigDecimal total = items.stream()
                .map(this::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        Long cashierId = user == null ? null : user.getId();
        orderService.checkoutAndClose(order.getId(), cashierId, method);
        recordHistory(tableNo, order.getId(), historyEntry(user, "satış yaptı. Tutar: "
                + formatCurrency(total) + ", Yöntem: " + (method == null ? "Belirtilmedi" : method.name())));
        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
        notifySalesChanged();
    }

    public synchronized List<SaleRecord> getSalesOn(LocalDate date) {
        return paymentService.getPaymentsOn(date).stream()
                .map(this::toSaleRecord)
                .collect(Collectors.toUnmodifiableList());
    }

    public synchronized List<SaleRecord> getSales() {
        return paymentService.getAllPayments().stream()
                .map(this::toSaleRecord)
                .collect(Collectors.toUnmodifiableList());
    }

    public synchronized List<ProductSalesRow> getProductSalesBefore(LocalDateTime threshold) {
        return reportsService.getProductSalesBefore(threshold);
    }

    public synchronized BigDecimal getSalesTotal(LocalDate date) {
        List<BigDecimal> amounts = paymentService.getPaymentsOn(date).stream()
                .map(Payment::getAmount)
                .collect(Collectors.toList());
        return sumAmounts(amounts);
    }

    public synchronized BigDecimal getSalesTotal(YearMonth yearMonth) {
        List<BigDecimal> amounts = paymentService.getPaymentsInMonth(yearMonth.getYear(), yearMonth.getMonthValue()).stream()
                .map(Payment::getAmount)
                .collect(Collectors.toList());
        return sumAmounts(amounts);
    }

    // ============================================================
    //   Gider İşlemleri
    // ============================================================

    public synchronized void addExpense(BigDecimal amount, String description, LocalDate date, User user) {
        Expense expense = new Expense();
        BigDecimal safeAmount = amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
        expense.setAmount(safeAmount);
        expense.setDescription(description);
        expense.setExpenseDate(date == null ? LocalDate.now() : date);
        expense.setUserId(user == null ? null : user.getId());
        expenseService.createExpense(expense);
        notifyExpensesChanged();
    }

    /**
     * Kg-bazlı gider ekleme. Toplam tutar = kilo × kgFiyat olarak hesaplanır.
     * Verilen description'a kg detayı eklenir (örn. "Domates (3 kg × 25 TL/kg)").
     */
    public synchronized void addKgBasedExpense(String description,
                                               BigDecimal quantityKg,
                                               BigDecimal unitPricePerKg,
                                               LocalDate date,
                                               User user) {
        if (quantityKg == null || quantityKg.signum() <= 0) {
            throw new IllegalArgumentException("Kilo sıfırdan büyük olmalı");
        }
        if (unitPricePerKg == null || unitPricePerKg.signum() < 0) {
            throw new IllegalArgumentException("Kg fiyatı negatif olamaz");
        }
        BigDecimal total = quantityKg.multiply(unitPricePerKg).setScale(2, RoundingMode.HALF_UP);
        String safeDesc = description == null ? "" : description.trim();
        if (safeDesc.isEmpty()) safeDesc = "Gider";
        String enriched = safeDesc + " (" + quantityKg.toPlainString() + " kg × " +
                unitPricePerKg.toPlainString() + " ₺/kg)";

        Expense expense = new Expense();
        expense.setAmount(total);
        expense.setDescription(enriched);
        expense.setExpenseDate(date == null ? LocalDate.now() : date);
        expense.setUserId(user == null ? null : user.getId());
        expense.setQuantityKg(quantityKg);
        expense.setUnitPricePerKg(unitPricePerKg);
        expenseService.createExpense(expense);
        notifyExpensesChanged();
    }

    public synchronized void deleteExpense(Long expenseId) {
        if (expenseId == null || expenseId <= 0) {
            throw new IllegalArgumentException("Geçersiz gider ID");
        }
        expenseService.deleteExpenseById(expenseId);
        notifyExpensesChanged();
    }

    public synchronized List<ExpenseRecord> getExpensesOn(LocalDate date) {
        return expenseService.getExpensesOn(date).stream()
                .map(this::toExpenseRecord)
                .collect(Collectors.toUnmodifiableList());
    }

    public synchronized List<ExpenseRecord> getExpenses() {
        return expenseService.getAllExpenses().stream()
                .map(this::toExpenseRecord)
                .collect(Collectors.toUnmodifiableList());
    }

    public synchronized BigDecimal getExpenseTotal(LocalDate date) {
        List<BigDecimal> amounts = expenseService.getExpensesOn(date).stream()
                .map(Expense::getAmount)
                .collect(Collectors.toList());
        return sumAmounts(amounts);
    }

    public synchronized BigDecimal getExpenseTotal(YearMonth yearMonth) {
        List<BigDecimal> amounts = expenseService.getExpensesInMonth(yearMonth).stream()
                .map(Expense::getAmount)
                .collect(Collectors.toList());
        return sumAmounts(amounts);
    }

    public synchronized BigDecimal getNetProfit(LocalDate date) {
        return getSalesTotal(date).subtract(getExpenseTotal(date)).setScale(2, RoundingMode.HALF_UP);
    }

    public synchronized BigDecimal getNetProfit(YearMonth yearMonth) {
        return getSalesTotal(yearMonth).subtract(getExpenseTotal(yearMonth)).setScale(2, RoundingMode.HALF_UP);
    }

    private TableLayout requireLayout(int tableNo) {
        TableLayout layout = layouts.get(tableNo);
        if (layout == null) {
            throw new IllegalArgumentException("Masa bulunamadı: " + tableNo);
        }
        return layout;
    }

    private Long ensureTableExists(int tableNo) {
        return tableIds.computeIfAbsent(tableNo, no -> {
            Optional<RestaurantTable> existing = tableService.getByTableNo(no);
            if (existing.isPresent()) {
                return existing.get().getId();
            }
            TableLayout layout = requireLayout(no);
            Long id = tableService.createTable(no, layout.building() + " / " + layout.section());
            return id;
        });
    }

    private Product ensureProduct(String name, BigDecimal price) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Ürün adı boş");
        }
        BigDecimal unitPrice = price == null ? BigDecimal.ZERO : price.setScale(2, RoundingMode.HALF_UP);
        Long categoryId = ensureDefaultCategoryId();
        Optional<Product> existing = productService.findByName(trimmed);
        if (existing.isPresent()) {
            Product product = existing.get();
            boolean dirty = false;
            if (product.getUnitPrice() == null || product.getUnitPrice().compareTo(unitPrice) != 0) {
                product.setUnitPrice(unitPrice);
                dirty = true;
            }
            if ((product.getCategoryId() == null || product.getCategoryId() <= 0) && categoryId != null) {
                product.setCategoryId(categoryId);
                dirty = true;
            }
            if (product.getStock() == null) {
                product.setStock(0);
                dirty = true;
            }
            if (dirty) {
                productService.updateProduct(product);
                notifyProductsChanged();
            }
            return product;
        }
        Product product = new Product();
        product.setName(trimmed);
        product.setUnitPrice(unitPrice);
        product.setVatRate(Product.DEFAULT_VAT);
        product.setStock(0);
        product.setCategoryId(categoryId);
        Long id = productService.createProduct(product);
        product.setId(id);
        notifyProductsChanged();
        return product;
    }

    private Long ensureDefaultCategoryId() {
        Long cached = defaultCategoryId.get();
        if (cached != null && cached > 0) {
            return cached;
        }
        synchronized (categoryLock) {
            cached = defaultCategoryId.get();
            if (cached != null && cached > 0) {
                return cached;
            }
            Category category = categoryService.findByName(DEFAULT_CATEGORY_NAME)
                    .orElseGet(this::createDefaultCategory);
            Long categoryId = category.getId();
            if (categoryId == null || categoryId <= 0) {
                Category fallback = selectFallbackCategory();
                if (fallback == null || fallback.getId() == null || fallback.getId() <= 0) {
                    throw new IllegalStateException("Varsayılan kategori oluşturulamadı");
                }
                if (!DEFAULT_CATEGORY_NAME.equalsIgnoreCase(optionalName(fallback))) {
                    LOG.warn("Varsayılan kategori '" + DEFAULT_CATEGORY_NAME
                            + "' bulunamadı. '" + optionalName(fallback) + "' kullanılacak.");
                }
                categoryId = fallback.getId();
            }
            defaultCategoryId.set(categoryId);
            return categoryId;
        }
    }

    private Category createDefaultCategory() {
        Category category = new Category();
        category.setName(DEFAULT_CATEGORY_NAME);
        category.setActive(true);
        Long id = categoryService.createCategory(category);
        category.setId(id);
        return category;
    }

    private Category selectFallbackCategory() {
        List<Category> categories = categoryService.getAllCategories();
        for (Category category : categories) {
            if (category != null && category.getId() != null && category.getId() > 0) {
                return category;
            }
        }
        return null;
    }

    private String optionalName(Category category) {
        return category == null ? "" : Optional.ofNullable(category.getName()).orElse("");
    }

    private String safeProductName(Product product) {
        if (product == null) {
            return "";
        }
        String name = product.getName();
        if (name == null) {
            return "";
        }
        return name.trim();
    }

    private List<Product> filterAndSortProducts(List<Product> products) {
        Comparator<Product> byName = Comparator.comparing(this::safeProductName, String.CASE_INSENSITIVE_ORDER);
        return products.stream()
                .filter(Objects::nonNull)
                .filter(Product::isActive)
                .filter(p -> !safeProductName(p).isEmpty())
                .sorted(byName)
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
    }

    /**
     * Pasif (tükendi) ürünler dahil tüm ürünleri sıralar.
     * Pasifler isim listesinin SONUNA gelir (önce aktifler, sonra tükenenler).
     */
    private List<Product> filterAndSortProductsAll(List<Product> products) {
        Comparator<Product> byActiveThenName = Comparator
                .comparing(Product::isActive).reversed()      // aktif (true) önce
                .thenComparing(this::safeProductName, String.CASE_INSENSITIVE_ORDER);
        return products.stream()
                .filter(Objects::nonNull)
                .filter(p -> !safeProductName(p).isEmpty())
                .sorted(byActiveThenName)
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
    }


    private OrderItem findOrderItem(Long orderId, String productName) {
        if (orderId == null || productName == null) {
            return null;
        }
        String target = productName.trim().toLowerCase();
        for (OrderItem item : orderService.getItemsForOrder(orderId)) {
            String name = resolveProductName(item);
            if (name != null && name.trim().toLowerCase().equals(target)) {
                return item;
            }
        }
        return null;
    }

    private OrderLine toOrderLine(OrderItem item) {
        String name = displayName(item); // ad çözme + "Ürün" fallback (tek yerde)
        BigDecimal unitPrice = resolveUnitPrice(item);
        int qty = Math.max(1, item.getQuantity());
        // pending = mutfağa henüz basılmadı (yeni "ek sipariş" kalemi)
        return new OrderLine(name, unitPrice, qty, item.isPending(), item.getNote(), item.getId(),
                item.getPiecesPerPortion(), item.getUnitLabel());
    }

    private BigDecimal lineTotal(OrderItem item) {
        BigDecimal unitPrice = resolveUnitPrice(item);
        return unitPrice.multiply(BigDecimal.valueOf(Math.max(0, item.getQuantity()))).setScale(2, RoundingMode.HALF_UP);
    }

    private String resolveProductName(OrderItem item) {
        String name = item.getProductName();
        if ((name == null || name.isBlank()) && item.getProductId() != null) {
            Product product = productService.getProductById(item.getProductId());
            if (product != null) {
                name = product.getName();
            }
        }
        return name;
    }

    private BigDecimal resolveUnitPrice(OrderItem item) {
        BigDecimal unitPrice = item.getUnitPrice();
        if ((unitPrice == null || unitPrice.signum() < 0) && item.getProductId() != null) {
            Product product = productService.getProductById(item.getProductId());
            if (product != null && product.getUnitPrice() != null) {
                unitPrice = product.getUnitPrice();
            }
        }
        if (unitPrice == null) {
            unitPrice = BigDecimal.ZERO;
        }
        return unitPrice.setScale(2, RoundingMode.HALF_UP);
    }

    private TableOrderStatus mapOrderStatus(OrderStatus status) {
        if (status == null) {
            return TableOrderStatus.ORDERED;
        }
        return switch (status) {
            case READY, COMPLETED -> TableOrderStatus.SERVED;
            case PENDING, IN_PROGRESS -> TableOrderStatus.ORDERED;
            default -> TableOrderStatus.EMPTY;
        };
    }

    private TableOrderStatus mapTableStatus(TableStatus status) {
        if (status == null) {
            return TableOrderStatus.EMPTY;
        }
        return switch (status) {
            case EMPTY -> TableOrderStatus.EMPTY;
            case RESERVED -> TableOrderStatus.SERVED;
            case OCCUPIED -> TableOrderStatus.ORDERED;
        };
    }

    private SaleRecord toSaleRecord(Payment payment) {
        int tableNo = -1;
        String building = "";
        String section = "";
        if (payment.getOrderId() != null) {
            Optional<Order> optOrder = orderService.getOrderById(payment.getOrderId());
            if (optOrder.isPresent()) {
                Order order = optOrder.get();
                Long tableId = order.getTableId();
                if (tableId != null) {
                    RestaurantTable table = tableService.getTableById(tableId).orElse(null);
                    if (table != null) {
                        tableNo = table.getTableNo();
                        tableIds.put(tableNo, table.getId());
                        TableLayout layout = layouts.get(tableNo);
                        if (layout != null) {
                            building = layout.building();
                            section = layout.section();
                        }
                    }
                }
            }
        }
        String performer = actor(payment.getCashierId());
        LocalDateTime timestamp = payment.getPaidAt();
        if (timestamp == null) {
            timestamp = payment.getCreatedAt();
        }
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
        BigDecimal amount = payment.getAmount() == null ? BigDecimal.ZERO : payment.getAmount();
        return new SaleRecord(tableNo, building, section, amount, payment.getMethod(), performer, timestamp);
    }

    private ExpenseRecord toExpenseRecord(Expense expense) {
        String performer = actor(expense.getUserId() == null
                ? null
                : userService.getUserById(expense.getUserId()).orElse(null));
        LocalDateTime created = expense.getCreatedAt();
        if (created == null) {
            created = LocalDateTime.now();
        }
        return new ExpenseRecord(expense.getId(), expense.getAmount(), expense.getDescription(), performer, expense.getExpenseDate(), created);
    }

    private void notifyTableChanged(int tableNo) {
        pcs.firePropertyChange(EVENT_TABLES, null, tableNo);
    }

    private void notifySalesChanged() {
        pcs.firePropertyChange(EVENT_SALES, null, null);
    }

    private void notifyExpensesChanged() {
        pcs.firePropertyChange(EVENT_EXPENSES, null, null);
    }

    private void notifyProductsChanged() {
        pcs.firePropertyChange(EVENT_PRODUCTS, null, null);
    }

    private BigDecimal sumAmounts(List<BigDecimal> amounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            if (amount != null) {
                total = total.add(amount);
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private Deque<OrderLogEntry> historyDeque(int tableNo) {
        return tableHistories.computeIfAbsent(tableNo, ignored -> new ArrayDeque<>());
    }

    private List<OrderLogEntry> resolveHistorySnapshot(int tableNo, List<OrderLogEntry> persisted) {
        Deque<OrderLogEntry> local = historyDeque(tableNo);
        if (persisted != null && !persisted.isEmpty()) {
            local.clear();
            for (OrderLogEntry entry : persisted) {
                local.addLast(entry);
            }
        }
        return List.copyOf(local);
    }

    private void recordHistory(int tableNo, Long orderId, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        Deque<OrderLogEntry> local = historyDeque(tableNo);
        local.addFirst(new OrderLogEntry(LocalDateTime.now(), message));
        while (local.size() > HISTORY_LIMIT) {
            local.removeLast();
        }
        if (orderId == null) {
            return;
        }
        try {
            orderLogService.append(orderId, message);
        } catch (RuntimeException ex) {
            LOG.warn("Sipariş geçmişi veritabanına kaydedilemedi: " + ex.getMessage());
        }
    }

    private String historyEntry(User user, String action) {
        return historyEntry(actor(user), action);
    }

    private String historyEntry(String actorName, String action) {
        String performer = (actorName == null || actorName.isBlank()) ? "Sistem" : actorName;
        String detail = action == null ? "" : action;
        return performer + OrderLogEntry.ACTOR_SEPARATOR + detail;
    }

    private String actor(@Nullable User user) {
        if (user == null) {
            return "Sistem";
        }
        String fullName = user.getFullName();
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }
        return Objects.toString(user.getUsername(), "Sistem");
    }

    private String actor(@NotNull Optional<User> user) {
        return actor(user.orElse(null));
    }

    private String actor(@Nullable Long userId) {
        if (userId == null) {
            return "Sistem";
        }
        return actor(userService.getUserById(userId));
    }

    private String formatCurrency(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void refreshTableSignature(int tableNo) {
        try {
            tableSignatures.put(tableNo, captureSignature(tableNo));
        } catch (RuntimeException ex) {
            LOG.warn("Masa durumu güncellenemedi: " + tableNo + " - " + ex.getMessage());
        }
    }

    private void pollChanges() {
        try {
            pollTables();
        } catch (Exception ex) {
            LOG.warn("pollTables hatası: {}", ex.getMessage(), ex);
        }
        try {
            pollSales();
        } catch (Exception ex) {
            LOG.warn("pollSales hatası: {}", ex.getMessage(), ex);
        }
        try {
            pollExpenses();
        } catch (Exception ex) {
            LOG.warn("pollExpenses hatası: {}", ex.getMessage(), ex);
        }
    }

    private void pollTables() {
        for (Integer tableNo : layouts.keySet()) {
            TableSignature newSignature = captureSignature(tableNo);
            TableSignature old = tableSignatures.put(tableNo, newSignature);
            if (!Objects.equals(old, newSignature)) {
                notifyTableChanged(tableNo);
            }
        }
    }

    private TableSignature captureSignature(int tableNo) {
        Long tableId = ensureTableExists(tableNo);
        TableStatus status = tableService.getByTableNo(tableNo)
                .map(RestaurantTable::getStatus)
                .orElse(TableStatus.EMPTY);
        Optional<Order> opt = orderService.getOpenOrderByTable(tableId);
        if (opt.isPresent()) {
            Order order = opt.get();
            LocalDateTime updated = order.getUpdatedAt();
            if (updated == null) {
                updated = order.getCreatedAt();
            }
            return new TableSignature(order.getId(), updated, status);
        }
        return new TableSignature(null, null, status);
    }

    private void pollSales() {
        SalesSignature signature = SalesSignature.from(paymentService.getAllPayments());
        SalesSignature previous = salesSignature.getAndSet(signature);
        if (!Objects.equals(previous, signature)) {
            notifySalesChanged();
        }
    }

    private void pollExpenses() {
        ExpensesSignature signature = ExpensesSignature.from(expenseService.getAllExpenses());
        ExpensesSignature previous = expensesSignature.getAndSet(signature);
        if (!Objects.equals(previous, signature)) {
            notifyExpensesChanged();
        }
    }

    private record TableLayout(int tableNo, String building, String section) {
    }

    private record TableSignature(Long orderId, LocalDateTime updatedAt, TableStatus tableStatus) {
    }

    private record SalesSignature(int count, long maxId, LocalDateTime latestPaidAt) {
        static SalesSignature empty() {
            return new SalesSignature(0, 0, null);
        }

        static SalesSignature from(List<Payment> payments) {
            int count = payments.size();
            long maxId = 0;
            LocalDateTime latest = null;
            for (Payment payment : payments) {
                if (payment.getId() != null && payment.getId() > maxId) {
                    maxId = payment.getId();
                }
                LocalDateTime paid = payment.getPaidAt();
                if (paid == null) {
                    paid = payment.getCreatedAt();
                }
                if (paid != null && (latest == null || paid.isAfter(latest))) {
                    latest = paid;
                }
            }
            return new SalesSignature(count, maxId, latest);
        }
    }

    private record ExpensesSignature(int count, long maxId, LocalDate latestDate) {
        static ExpensesSignature empty() {
            return new ExpensesSignature(0, 0, null);
        }

        static ExpensesSignature from(List<Expense> expenses) {
            int count = expenses.size();
            long maxId = 0;
            LocalDate latest = null;
            for (Expense expense : expenses) {
                if (expense.getId() != null && expense.getId() > maxId) {
                    maxId = expense.getId();
                }
                LocalDate date = expense.getExpenseDate();
                if (date != null && (latest == null || date.isAfter(latest))) {
                    latest = date;
                }
            }
            return new ExpensesSignature(count, maxId, latest);
        }
    }
}