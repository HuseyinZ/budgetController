package dao;

import model.Order;
import model.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderDAO extends CrudRepository<Order, Long> {
    List<Order> findOpenOrders();
    Optional<Order> findOpenOrderByTable(Long tableId);
    void updateStatus(Long orderId, OrderStatus status);
    void assignTable(Long orderId, Long tableId);
    /** Siparişi kapatır: closed_at set edilir ve status 'COMPLETED' yapılır. */
    void closeOrder(Long orderId, LocalDateTime closedAt);
    void updateTotals(Long orderId, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal discountTotal, BigDecimal total);
}
