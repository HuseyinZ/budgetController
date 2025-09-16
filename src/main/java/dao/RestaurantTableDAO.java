package dao;

import model.RestaurantTable;
import model.TableStatus;

import java.util.List;
import java.util.Optional;

public interface RestaurantTableDAO extends CrudRepository<RestaurantTable, Long> {
    Optional<RestaurantTable> findByTableNo(int tableNo);
    List<RestaurantTable> findByStatus(TableStatus status);
    void updateStatus(Long tableId, TableStatus status);
    void setNote(Long tableId, String note);
}
