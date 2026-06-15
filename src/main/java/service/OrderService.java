package service;

import DataConnection.Db;
import DataConnection.TransactionExecutor;
import dao.OrderDAO;
import dao.OrderItemsDAO;
import dao.PaymentDAO;
import dao.ProductDAO;
import dao.RestaurantTableDAO;
import dao.UserDAO;
import dao.jdbc.OrderItemsJdbcDAO;
import dao.jdbc.OrderJdbcDAO;
import dao.jdbc.PaymentJdbcDAO;
import dao.jdbc.ProductJdbcDAO;
import dao.jdbc.RestaurantTableJdbcDAO;
import dao.jdbc.UserJdbcDAO;
import model.Order;
import model.OrderItem;
import model.OrderStatus;
import model.Payment;
import model.PaymentMethod;
import model.Product;
import model.RestaurantTable;
import model.TableStatus;
import model.User;
import service.print.PrintingService;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public class OrderService {

    private final OrderDAO orderDAO;
    private final OrderItemsDAO orderItemsDAO;
    private final ProductDAO productDAO;
    private final PaymentDAO paymentDAO;
    private final RestaurantTableDAO tableDAO;

    private final Function<Connection, OrderDAO> orderDaoFactory;
    private final Function<Connection, OrderItemsDAO> orderItemsDaoFactory;
    private final Function<Connection, ProductDAO> productDaoFactory;
    private final Function<Connection, PaymentDAO> paymentDaoFactory;
    private final Function<Connection, RestaurantTableDAO> tableDaoFactory;
    private final TransactionExecutor txExecutor;

    public OrderService() {
        this(new OrderJdbcDAO(), new OrderItemsJdbcDAO(), new ProductJdbcDAO(),
                new PaymentJdbcDAO(), new RestaurantTableJdbcDAO());
    }

    public OrderService(OrderDAO orderDAO,
                        OrderItemsDAO orderItemsDAO,
                        ProductDAO productDAO,
                        PaymentDAO paymentDAO,
                        RestaurantTableDAO tableDAO) {
        this(orderDAO, orderItemsDAO, productDAO, paymentDAO, tableDAO,
                OrderJdbcDAO::new, OrderItemsJdbcDAO::new, ProductJdbcDAO::new,
                PaymentJdbcDAO::new, RestaurantTableJdbcDAO::new, Db::tx);
    }

    public OrderService(OrderDAO orderDAO,
                        OrderItemsDAO orderItemsDAO,
                        ProductDAO productDAO,
                        PaymentDAO paymentDAO,
                        RestaurantTableDAO tableDAO,
                        Function<Connection, OrderDAO> orderDaoFactory,
                        Function<Connection, OrderItemsDAO> orderItemsDaoFactory,
                        Function<Connection, ProductDAO> productDaoFactory,
                        Function<Connection, PaymentDAO> paymentDaoFactory,
                        Function<Connection, RestaurantTableDAO> tableDaoFactory,
                        TransactionExecutor txExecutor) {
        this.orderDAO = Objects.requireNonNull(orderDAO, "orderDAO");
        this.orderItemsDAO = Objects.requireNonNull(orderItemsDAO, "orderItemsDAO");
        this.productDAO = Objects.requireNonNull(productDAO, "productDAO");
        this.paymentDAO = Objects.requireNonNull(paymentDAO, "paymentDAO");
        this.tableDAO = Objects.requireNonNull(tableDAO, "tableDAO");
        this.orderDaoFactory = Objects.requireNonNull(orderDaoFactory, "orderDaoFactory");
        this.orderItemsDaoFactory = Objects.requireNonNull(orderItemsDaoFactory, "orderItemsDaoFactory");
        this.productDaoFactory = Objects.requireNonNull(productDaoFactory, "productDaoFactory");
        this.paymentDaoFactory = Objects.requireNonNull(paymentDaoFactory, "paymentDaoFactory");
        this.tableDaoFactory = Objects.requireNonNull(tableDaoFactory, "tableDaoFactory");
        this.txExecutor = Objects.requireNonNull(txExecutor, "txExecutor");
    }

    public Optional<Order> getOrderById(Long orderId) {
        return orderDAO.findById(orderId);
    }

    public List<Order> getOpenOrders() {
        return orderDAO.findOpenOrders();
    }

    public Optional<Order> getOpenOrderByTable(Long tableId) {
        return orderDAO.findOpenOrderByTable(tableId);
    }

    public Order createOrder(Long tableId, Long waiterId) {
        return txExecutor.execute(conn -> {
            OrderDAO txOrder = orderDaoFactory.apply(conn);
            RestaurantTableDAO txTable = tableDaoFactory.apply(conn);
            Order order = new Order(tableId, waiterId, OrderStatus.PENDING);
            Long id = txOrder.create(order);
            if (id == null || id <= 0) {
                throw new IllegalStateException("Order create failed");
            }
            order.setId(id);
            if (tableId != null) {
                txTable.updateStatus(tableId, TableStatus.OCCUPIED);
            }
            return order;
        });
    }

    public void checkoutAndClose(Long orderId, Long cashierUserId, PaymentMethod method) {
        txExecutor.execute(conn -> {
            OrderItemsDAO txItems = orderItemsDaoFactory.apply(conn);
            OrderDAO txOrders = orderDaoFactory.apply(conn);
            PaymentDAO txPayments = paymentDaoFactory.apply(conn);
            RestaurantTableDAO txTables = tableDaoFactory.apply(conn);

            BigDecimal subtotal = BigDecimal.ZERO;
            BigDecimal taxTotal = BigDecimal.ZERO;
            BigDecimal total = BigDecimal.ZERO;

            List<OrderItem> items = txItems.findByOrderId(orderId);
            for (OrderItem it : items) {
                if (it.getNetAmount() != null) subtotal = subtotal.add(it.getNetAmount());
                if (it.getTaxAmount() != null) taxTotal = taxTotal.add(it.getTaxAmount());
                if (it.getLineTotal() != null) total = total.add(it.getLineTotal());
            }
            txOrders.updateTotals(orderId, subtotal, taxTotal, BigDecimal.ZERO, total);

            Payment p = new Payment();
            p.setOrderId(orderId);
            p.setCashierId(cashierUserId);
            p.setAmount(total);
            p.setMethod(method);
            txPayments.create(p);

            txOrders.closeOrder(orderId, LocalDateTime.now());

            txOrders.findById(orderId).ifPresent(o -> {
                if (o.getTableId() != null) {
                    txTables.updateStatus(o.getTableId(), TableStatus.EMPTY);
                }
            });
            return null;
        });
    }

    public void addItemToOrder(Long orderId, Long productId, int quantity) {
        addItemToOrder(orderId, productId, quantity, null);
    }

    /**
     * Şiş/birim bazlı eklemeyi destekleyen overload.
     *
     * <p>{@code pieces} parametresi {@code null} ise ürün PORSİYON bazlı kabul edilir
     * (eski davranış; quantity = porsiyon, unitPrice = porsiyon fiyatı).
     *
     * <p>{@code pieces} dolu ise ürün ŞİŞ bazlı kabul edilir:
     * <ul>
     *   <li>order_items.quantity = pieces (toplam şiş)</li>
     *   <li>order_items.unit_price = porsiyon_fiyatı / pieces_per_portion (şiş başına)</li>
     *   <li>order_items.pieces_per_portion ve unit_label snapshot olarak yazılır</li>
     * </ul>
     *
     * <p>Fiyat hesabı: line_total = quantity × unit_price = pieces × (porsiyon_fiyatı / pieces_per_portion)
     * — yani <i>porsiyon ücreti × (sipariş şiş / porsiyon şiş)</i>.
     */
    public void addItemToOrder(Long orderId, Long productId, int quantity, Integer pieces) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity > 0 olmalı");

        txExecutor.execute(conn -> {
            ProductDAO txProduct = productDaoFactory.apply(conn);
            OrderItemsDAO txItems = orderItemsDaoFactory.apply(conn);

            Product product = txProduct.findById(productId).orElseThrow();
            // Stok kontrolü iptal edildi — sistem stok yönetmiyor (UI'dan gizli).

            BigDecimal unitPrice;
            Integer ppSnapshot;
            String labelSnapshot;
            int qtyForDb;
            if (pieces != null && pieces > 0 && product.isPieceBased()) {
                // Şiş bazlı: birim fiyatı = porsiyon / piecesPerPortion
                unitPrice = product.getPerPiecePrice();
                qtyForDb = pieces;
                ppSnapshot = product.getPiecesPerPortion();
                labelSnapshot = product.getUnitLabel();
            } else {
                // Porsiyon bazlı (eski davranış)
                unitPrice = product.getUnitPrice();
                qtyForDb = quantity;
                ppSnapshot = null;
                labelSnapshot = product.getUnitLabel();
            }
            txItems.addOrIncrement(orderId, productId, product.getName(),
                    qtyForDb, unitPrice, ppSnapshot, labelSnapshot);
            txProduct.updateStock(productId, -qtyForDb);
            return null;
        });
    }

    public void decrementItem(Long orderItemId, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity > 0 olmalı");

        txExecutor.execute(conn -> {
            OrderItemsDAO txItems = orderItemsDaoFactory.apply(conn);
            ProductDAO txProduct = productDaoFactory.apply(conn);

            OrderItem item = txItems.findById(orderItemId).orElseThrow();
            if (item.getProductId() != null) {
                txProduct.updateStock(item.getProductId(), quantity);
            }
            txItems.decrementOrRemove(orderItemId, quantity);
            return null;
        });
    }

    public void clearItems(Long orderId) {
        txExecutor.execute(conn -> {
            OrderItemsDAO txItems = orderItemsDaoFactory.apply(conn);
            ProductDAO txProduct = productDaoFactory.apply(conn);

            List<OrderItem> items = txItems.findByOrderId(orderId);
            for (OrderItem it : items) {
                if (it.getProductId() != null) {
                    txProduct.updateStock(it.getProductId(), it.getQuantity());
                }
            }
            txItems.removeAllForOrder(orderId);
            return null;
        });
    }

    public void reassignTable(Long orderId, Long newTableId) {
        txExecutor.execute(conn -> {
            OrderDAO txOrders = orderDaoFactory.apply(conn);
            RestaurantTableDAO txTables = tableDaoFactory.apply(conn);

            Long oldTableId = txOrders.findById(orderId).map(Order::getTableId).orElse(null);
            txOrders.assignTable(orderId, newTableId);
            if (oldTableId != null && !oldTableId.equals(newTableId)) {
                txTables.updateStatus(oldTableId, TableStatus.EMPTY);
            }
            if (newTableId != null) {
                txTables.updateStatus(newTableId, TableStatus.OCCUPIED);
            }
            return null;
        });
    }

    public void recomputeTotals(Long orderId) {
        txExecutor.execute(conn -> {
            OrderItemsDAO txItems = orderItemsDaoFactory.apply(conn);
            OrderDAO txOrders = orderDaoFactory.apply(conn);

            BigDecimal subtotal = BigDecimal.ZERO;
            BigDecimal taxTotal = BigDecimal.ZERO;
            BigDecimal total = BigDecimal.ZERO;

            for (OrderItem it : txItems.findByOrderId(orderId)) {
                if (it.getNetAmount() != null) subtotal = subtotal.add(it.getNetAmount());
                if (it.getTaxAmount() != null) taxTotal = taxTotal.add(it.getTaxAmount());
                if (it.getLineTotal() != null) total = total.add(it.getLineTotal());
            }
            txOrders.updateTotals(orderId, subtotal, taxTotal, BigDecimal.ZERO, total);
            return null;
        });
    }

    public List<OrderItem> getItemsForOrder(Long orderId) {
        return orderItemsDAO.findByOrderId(orderId);
    }

    public void updateOrderStatus(Long orderId, OrderStatus status) {
        orderDAO.updateStatus(orderId, status);
    }

    /**
     * Bir siparişin tüm bekleyen (printed_at IS NULL) kalemlerini "basıldı"
     * olarak işaretler. "Sipariş hazır" akışında çağrılır — UI'da YENİ
     * etiketinin temizlenmesini sağlar.
     */
    public void markAllItemsPrinted(Long orderId) {
        if (orderId == null) return;
        orderItemsDAO.markItemsPrinted(orderId);
    }

    /** Bir sipariş kaleminin notunu günceller. */
    public void updateItemNote(Long orderItemId, String note) {
        if (orderItemId == null) return;
        orderItemsDAO.updateNote(orderItemId, note);
    }

    // ============================================================
    //   Mutfak fişi gönderimi (2026-05-15 entegrasyonu)
    // ============================================================

    /**
     * Bir siparişin tüm kalemlerini ürün kategorisine göre gruplayıp
     * ilgili mutfak yazıcılarına basar.
     *
     * <p>Çağrı şekli:
     * <pre>{@code
     *   orderService.sendToKitchens(orderId, new PrintingService());
     * }</pre>
     *
     * <p>PrintingService null verilirse hiçbir şey yapılmaz — bu sayede
     * yazıcı donanımı henüz takılmadığında uygulama çökmez.
     *
     * @return printingService.PrintResult listesi (loglama / UI için).
     *         Servis null ise boş liste.
     */
    public List<PrintingService.PrintResult> sendToKitchens(Long orderId,
                                                            PrintingService printingService) {
        return sendToKitchens(orderId, /*salonName*/ null, printingService);
    }

    /**
     * Aynı fonksiyonun salon adını dışarıdan geçirebilen overload'ı.
     * <p>UI tarafı {@code TableSnapshot.getBuilding() + " / " + getSection()}
     * birleşimini buraya geçirebilir.
     */
    public List<PrintingService.PrintResult> sendToKitchens(Long orderId,
                                                            String salonName,
                                                            PrintingService printingService) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId null olamaz");
        }
        if (printingService == null) {
            return List.of();   // yazıcı yokken sessiz geç
        }

        Order order = orderDAO.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order bulunamadı: " + orderId));

        List<OrderItem> allItems = orderItemsDAO.findByOrderId(orderId);
        if (allItems.isEmpty()) {
            return List.of();
        }

        // ➜ Sadece henüz mutfağa basılmamış (printed_at IS NULL) kalemleri gönder.
        //   Bu sayede "ek sipariş" senaryosunda eski kalemler yeniden basılmaz.
        List<OrderItem> pendingItems = new java.util.ArrayList<>();
        for (OrderItem it : allItems) {
            if (it.isPending()) pendingItems.add(it);
        }
        if (pendingItems.isEmpty()) {
            return List.of();   // gönderilecek yeni kalem yok
        }

        String tableNo = "-";
        if (order.getTableId() != null) {
            RestaurantTable t = tableDAO.findById(order.getTableId()).orElse(null);
            if (t != null && t.getTableNo() != null) {
                tableNo = String.valueOf(t.getTableNo());
            }
        }

        String waiterName = "-";
        if (order.getWaiterId() != null) {
            UserDAO userDAO = new UserJdbcDAO();
            Optional<User> u = userDAO.findById(order.getWaiterId());
            if (u.isPresent()) {
                User w = u.get();
                waiterName = (w.getFullName() != null && !w.getFullName().isBlank())
                        ? w.getFullName() : w.getUsername();
            }
        }

        String note = order.getNote();
        List<PrintingService.PrintResult> results = printingService.sendOrderToKitchens(orderId,
                salonName == null ? "" : salonName,
                tableNo, waiterName, note, pendingItems);

        // Başarılı baskı(lar) varsa kalemleri "basıldı" olarak işaretle
        boolean anySuccess = results.stream().anyMatch(r -> r.success);
        if (anySuccess) {
            orderItemsDAO.markItemsPrinted(orderId);
        }
        return results;
    }
}
