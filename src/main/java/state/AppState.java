package state;

import model.Category;
import model.Expense;
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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    public static final String EVENT_TABLES = "tables";
    public static final String EVENT_SALES = "sales";
    public static final String EVENT_EXPENSES = "expenses";
    public static final String EVENT_PRODUCTS = "products";
    private static final int HISTORY_LIMIT = 50;
    private static final String DEFAULT_CATEGORY_NAME = "Genel";

    public static final class AreaDefinition {
        private final String building;
        private final String section;
        private final int startTableNo;
        private final int tableCount;

        public AreaDefinition(String building, String section, int startTableNo, int tableCount) {
            this.building = building;
            this.section = section;
            this.startTableNo = startTableNo;
            this.tableCount = tableCount;
        }

        public String getBuilding() {
            return building;
        }

        public String getSection() {
            return section;
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

    private final Map<Integer, TableLayout> layouts = new LinkedHashMap<>();
    private final Map<Integer, Long> tableIds = new ConcurrentHashMap<>();
    private final Map<Integer, TableSignature> tableSignatures = new ConcurrentHashMap<>();
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
        this.productService = new ProductService();
        this.categoryService = new CategoryService();
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

    private List<AreaDefinition> createDefaultAreas() {
        List<AreaDefinition> defs = new ArrayList<>();
        defs.add(new AreaDefinition("1. Bina", "1. Kat", 101, 10));
        defs.add(new AreaDefinition("1. Bina", "2. Kat", 111, 10));
        defs.add(new AreaDefinition("1. Bina", "3. Kat", 121, 10));
        defs.add(new AreaDefinition("2. Bina", "1. Kat", 201, 10));
        defs.add(new AreaDefinition("2. Bina", "2. Kat", 211, 10));
        defs.add(new AreaDefinition("2. Bina", "3. Kat", 221, 10));
        defs.add(new AreaDefinition("3. Bina", "Bahçe", 301, 10));
        return Collections.unmodifiableList(defs);
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
                System.err.println("Masa senkronizasyonu başarısız: " + tableNo + " - " + ex.getMessage());
            }
        }
    }

    public List<AreaDefinition> getAreas() {
        return areas;
    }

    public synchronized List<Product> getAvailableProducts() {
        return filterAndSortProducts(productService.getAllProducts());
    }

    public synchronized List<Product> getProductsByCategoryName(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return getAvailableProducts();
        }
        return filterAndSortProducts(productService.getProductsByCategoryName(categoryName));
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

    public synchronized List<Category> getAllCategories() {
        return categoryService.getAllCategories();
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
            history = List.copyOf(orderLogService.getRecentLogs(order.getId(), HISTORY_LIMIT));
        } else {
            TableStatus tableStatus = tableService.getByTableNo(tableNo)
                    .map(RestaurantTable::getStatus)
                    .orElse(TableStatus.EMPTY);
            status = mapTableStatus(tableStatus);
        }

        return new TableSnapshot(tableNo, layout.building(), layout.section(), status, lines, history, total);
    }

    public synchronized BigDecimal getTableTotal(int tableNo) {
        return snapshot(tableNo).getTotal();
    }

    public synchronized TableOrderStatus getTableStatus(int tableNo) {
        return snapshot(tableNo).getStatus();
    }

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
        boolean restockApplied = false;
        try {
            productService.increaseProductStock(productId, quantity, "virtual-restock");
            restockApplied = true;
            orderService.addItemToOrder(order.getId(), productId, quantity);
        } catch (RuntimeException ex) {
            if (restockApplied) {
                try {
                    productService.decreaseProductStock(productId, quantity);
                } catch (RuntimeException rollbackEx) {
                    System.err.println("Ürün stok güncellemesi geri alınamadı: " + rollbackEx.getMessage());
                }
            }
            throw ex;
        }
        orderService.updateOrderStatus(order.getId(), OrderStatus.IN_PROGRESS);
        orderService.recomputeTotals(order.getId());
        tableService.markTableOccupied(tableId, true);
        String productLabel = safeProductName(resolved);
        if (productLabel.isEmpty()) {
            productLabel = "Ürün";
        }
        orderLogService.append(order.getId(), actor(user) + " " + quantity + " x " + productLabel + " ekledi");
        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
    }

    public synchronized void decreaseItem(int tableNo, String productName, int quantity, User user) {
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
        orderService.decrementItem(item.getId(), quantity);
        if (item.getProductId() != null) {
            productService.decreaseProductStock(item.getProductId(), quantity);
        }
        orderService.recomputeTotals(order.getId());
        orderLogService.append(order.getId(), actor(user) + " " + quantity + " x " + productName + " azalttı");
        if (orderService.getItemsForOrder(order.getId()).isEmpty()) {
            orderService.updateOrderStatus(order.getId(), OrderStatus.PENDING);
        }
        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
    }

    public synchronized void removeItem(int tableNo, String productName, User user) {
        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId).orElse(null);
        if (order == null) {
            return;
        }
        OrderItem item = findOrderItem(order.getId(), productName);
        if (item == null) {
            return;
        }
        int qty = item.getQuantity();
        orderService.decrementItem(item.getId(), qty);
        if (item.getProductId() != null && qty > 0) {
            productService.decreaseProductStock(item.getProductId(), qty);
        }
        orderService.recomputeTotals(order.getId());
        orderLogService.append(order.getId(), actor(user) + " " + productName + " ürününü sildi");
        if (orderService.getItemsForOrder(order.getId()).isEmpty()) {
            orderService.updateOrderStatus(order.getId(), OrderStatus.PENDING);
        }
        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
    }

    public synchronized void clearTable(int tableNo, User user) {
        Long tableId = ensureTableExists(tableNo);
        Order order = orderService.getOpenOrderByTable(tableId).orElse(null);
        if (order == null) {
            tableService.markTableOccupied(tableId, false);
            refreshTableSignature(tableNo);
            notifyTableChanged(tableNo);
            return;
        }
        List<OrderItem> items = orderService.getItemsForOrder(order.getId());
        orderService.clearItems(order.getId());
        for (OrderItem item : items) {
            if (item.getProductId() != null && item.getQuantity() > 0) {
                productService.decreaseProductStock(item.getProductId(), item.getQuantity());
            }
        }
        orderService.updateOrderStatus(order.getId(), OrderStatus.CANCELLED);
        orderService.reassignTable(order.getId(), null);
        orderLogService.append(order.getId(), actor(user) + " masayı temizledi");
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
        if (tableReserveUnsupported) {
            tableService.markTableOccupied(tableId, true);
        } else {
            try {
                tableService.markTableReserved(tableId);
            } catch (RuntimeException ex) {
                tableReserveUnsupported = true;
                System.err.println("Masa durumu 'RESERVED' olarak işaretlenemedi. 'OCCUPIED' kullanılacak. Ayrıntı: "
                        + ex.getMessage());
                tableService.markTableOccupied(tableId, true);
            }
        }
        orderLogService.append(order.getId(), actor(user) + " siparişi servis etti");
        refreshTableSignature(tableNo);
        notifyTableChanged(tableNo);
    }

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
        orderLogService.append(order.getId(), actor(user) + " satış yaptı. Tutar: "
                + formatCurrency(total) + ", Yöntem: " + (method == null ? "Belirtilmedi" : method.name()));
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
                    System.err.println("Varsayılan kategori '" + DEFAULT_CATEGORY_NAME
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
        String name = resolveProductName(item);
        if (name == null || name.isBlank()) {
            name = "Ürün";
        }
        BigDecimal unitPrice = resolveUnitPrice(item);
        int qty = Math.max(1, item.getQuantity());
        return new OrderLine(name, unitPrice, qty);
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
            System.err.println("Masa durumu güncellenemedi: " + tableNo + " - " + ex.getMessage());
        }
    }

    private void pollChanges() {
        try {
            pollTables();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        try {
            pollSales();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        try {
            pollExpenses();
        } catch (Exception ex) {
            ex.printStackTrace();
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
