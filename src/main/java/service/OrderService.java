package service;

import dao.*;
import dao.jdbc.OrderJdbcDAO;
import dao.jdbc.OrderItemsJdbcDAO;
import dao.jdbc.PaymentJdbcDAO;
import dao.jdbc.ProductJdbcDAO;
import model.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class OrderService {

    // DAO’ların JDBC implementasyonları
    private final OrderDAO orderDAO = new OrderJdbcDAO();
    private final OrderItemsDAO orderItemsDAO = new OrderItemsJdbcDAO();
    private final ProductDAO productDAO = new ProductJdbcDAO();
    private final PaymentDAO paymentDAO = new PaymentJdbcDAO();

    // Masa durumunu güncellemek için service’e ihtiyacımız var
    private final RestaurantTableService tableService;

    public OrderService(RestaurantTableService tableService) {
        this.tableService = tableService;
    }

    /* ===================== SORGULAR ===================== */

    public Optional<Order> getOrderById(Long orderId) {
        return orderDAO.findById(orderId);
    }

    public List<Order> getOpenOrders() {
        return orderDAO.findOpenOrders();
    }

    public Optional<Order> getOpenOrderByTable(Long tableId) {
        return orderDAO.findOpenOrderByTable(tableId);
    }

    /* ===================== SİPARİŞ OLUŞTUR/KAPAT ===================== */

    public Order createOrder(Long tableId, Long waiterId) {
        // Order modelinde parametreli ctor var: (tableId, waiterId, status)
        Order order = new Order(tableId, waiterId, OrderStatus.PENDING);
        Long id = orderDAO.create(order);
        if (id == null || id <= 0) {
            throw new IllegalStateException("Order create failed");
        }
        order.setId(id);

        // Masa dolu
        if (tableId != null) {
            tableService.markTableOccupied(tableId, true);
        }
        return order;
    }

    /** Siparişi kapat + ödeme kaydet + masayı boşalt. */
    // service/OrderService.java
    public void checkoutAndClose(Long orderId, Long cashierUserId, PaymentMethod method) {
        // 1) Kalemlerden toplamları topla
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal total    = BigDecimal.ZERO;

        List<OrderItem> items = orderItemsDAO.findByOrderId(orderId);
        for (OrderItem it : items) {
            if (it.getNetAmount()  != null) subtotal = subtotal.add(it.getNetAmount());
            if (it.getTaxAmount()  != null) taxTotal = taxTotal.add(it.getTaxAmount());
            if (it.getLineTotal()  != null) total    = total.add(it.getLineTotal());
        }
        orderDAO.updateTotals(orderId, subtotal, taxTotal, BigDecimal.ZERO, total);

        // 2) Ödeme kaydı
        Payment p = new Payment();
        p.setOrderId(orderId);
        p.setCashierId(cashierUserId);
        p.setAmount(total);
        p.setMethod(method);
        paymentDAO.create(p);

        // 3) Siparişi KAPAT (ve durumu COMPLETED yap) — tek UPDATE
        orderDAO.closeOrder(orderId, LocalDateTime.now());

        // 4) Masayı boşalt
        orderDAO.findById(orderId).ifPresent(o -> {
            if (o.getTableId() != null) {
                tableService.markTableOccupied(o.getTableId(), false);
            }
        });
    }


    /* ===================== KALEM İŞLEMLERİ ===================== */

    /** Siparişe ürün ekle (yoksa ekler, varsa miktarı artırır). Stok düşer. */
    public void addItemToOrder(Long orderId, Long productId, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity > 0 olmalı");

        Product product = productDAO.findById(productId).orElseThrow();
        // Stok kontrolü (modelde getStock var)
        Integer stock = null;
        try { stock = (Integer) product.getClass().getMethod("getStock").invoke(product); }
        catch (Exception ignore) { /* Product'ta getStock yoksa DAO updateStock yine çalışır */ }

        if (stock != null && stock < quantity) {
            throw new IllegalStateException("Stok yetersiz");
        }

        BigDecimal unitPrice = product.getUnitPrice(); // snapshot fiyatı

        // Aynı ürün varsa miktarı artır, yoksa yeni satır ekle
        orderItemsDAO.addOrIncrement(orderId, productId, quantity, unitPrice);

        // Stok düş
        productDAO.updateStock(productId, -quantity);
    }

    /** Kalem miktarını azalt; sıfıra inerse siler. Stoku iade eder. */
    public void decrementItem(Long orderItemId, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity > 0 olmalı");

        OrderItem item = orderItemsDAO.findById(orderItemId).orElseThrow();

        // İade stok
        productDAO.updateStock(item.getProductId(), +quantity);

        // Kalemi azalt/0 ise sil
        orderItemsDAO.decrementOrRemove(orderItemId, quantity);
    }

    /** Siparişten tüm kalemleri siler (ör. iptal). */
    public void clearItems(Long orderId) {
        // Kalemleri al, stok iade et
        List<OrderItem> items = orderItemsDAO.findByOrderId(orderId);
        for (OrderItem it : items) {
            productDAO.updateStock(it.getProductId(), +it.getQuantity());
        }
        // Kalemleri sil
        orderItemsDAO.removeAllForOrder(orderId);
    }

    /* ===================== DİĞER YARDIMCI ===================== */

    /** Siparişin masa atamasını değiştir ve masa doluluklarını güncelle. */
    public void reassignTable(Long orderId, Long newTableId) {
        Long oldTableId = orderDAO.findById(orderId).map(Order::getTableId).orElse(null);
        orderDAO.assignTable(orderId, newTableId);
        if (oldTableId != null && !oldTableId.equals(newTableId)) {
            tableService.markTableOccupied(oldTableId, false);
        }
        if (newTableId != null) {
            tableService.markTableOccupied(newTableId, true);
        }
    }

    /** Sipariş toplamlarını tekrar hesaplayıp yazar. (İstediğin yerde çağırabilirsin.) */
    public void recomputeTotals(Long orderId) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;

        for (OrderItem it : orderItemsDAO.findByOrderId(orderId)) {
            if (it.getNetAmount() != null)  subtotal = subtotal.add(it.getNetAmount());
            if (it.getTaxAmount() != null)  taxTotal = taxTotal.add(it.getTaxAmount());
            if (it.getLineTotal() != null)  total    = total.add(it.getLineTotal());
        }
        orderDAO.updateTotals(orderId, subtotal, taxTotal, BigDecimal.ZERO, total);
    }
}
