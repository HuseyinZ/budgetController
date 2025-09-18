package service;

import DataConnection.Db;
import DataConnection.TransactionExecutor;
import dao.OrderDAO;
import dao.OrderItemsDAO;
import dao.PaymentDAO;
import dao.ProductDAO;
import dao.RestaurantTableDAO;
import dao.jdbc.OrderItemsJdbcDAO;
import dao.jdbc.OrderJdbcDAO;
import dao.jdbc.PaymentJdbcDAO;
import dao.jdbc.ProductJdbcDAO;
import dao.jdbc.RestaurantTableJdbcDAO;
import model.Order;
import model.OrderItem;
import model.OrderStatus;
import model.Payment;
import model.PaymentMethod;
import model.Product;
import model.TableStatus;

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
        if (quantity <= 0) throw new IllegalArgumentException("quantity > 0 olmalı");

        txExecutor.execute(conn -> {
            ProductDAO txProduct = productDaoFactory.apply(conn);
            OrderItemsDAO txItems = orderItemsDaoFactory.apply(conn);

            Product product = txProduct.findById(productId).orElseThrow();
            Integer stock = product.getStock();
            if (stock != null && stock < quantity) {
                throw new IllegalStateException("Stok yetersiz");
            }

            BigDecimal unitPrice = product.getUnitPrice();
            txItems.addOrIncrement(orderId, productId, product.getName(), quantity, unitPrice);
            txProduct.updateStock(productId, -quantity);
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
}
