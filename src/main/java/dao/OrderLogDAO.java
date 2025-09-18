package dao;

import state.OrderLogEntry;

import java.util.List;

public interface OrderLogDAO {
    void append(Long orderId, String message);
    List<OrderLogEntry> findRecentByOrder(Long orderId, int limit);
}
