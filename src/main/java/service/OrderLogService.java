package service;

import dao.OrderLogDAO;
import dao.jdbc.OrderLogJdbcDAO;
import state.OrderLogEntry;

import java.util.List;
import java.util.Objects;

public class OrderLogService {

    private final OrderLogDAO orderLogDAO;

    public OrderLogService() {
        this(new OrderLogJdbcDAO());
    }

    public OrderLogService(OrderLogDAO orderLogDAO) {
        this.orderLogDAO = Objects.requireNonNull(orderLogDAO, "orderLogDAO");
    }

    public void append(Long orderId, String message) {
        orderLogDAO.append(orderId, message);
    }

    public List<OrderLogEntry> getRecentLogs(Long orderId, int limit) {
        return orderLogDAO.findRecentByOrder(orderId, limit);
    }
}
