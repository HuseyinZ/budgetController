package dao;

import model.OrderItem;

import java.math.BigDecimal;
import java.util.List;

public interface OrderItemsDAO extends CrudRepository<OrderItem, Long> {
    List<OrderItem> findByOrderId(Long orderId);
    void addOrIncrement(Long orderId, Long productId, String productName, int qty, BigDecimal unitPrice);
    void decrementOrRemove(Long orderItemId, int qty);
    void removeAllForOrder(Long orderId);
}
