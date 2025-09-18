package service;

import dao.RestaurantTableDAO;
import dao.jdbc.RestaurantTableJdbcDAO;
import model.RestaurantTable;
import model.TableStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class RestaurantTableService {

    private final RestaurantTableDAO tableDAO;

    public RestaurantTableService() {
        this(new RestaurantTableJdbcDAO());
    }

    public RestaurantTableService(RestaurantTableDAO tableDAO) {
        this.tableDAO = Objects.requireNonNull(tableDAO, "tableDAO");
    }

    /* --------- Sorgular --------- */

    public List<RestaurantTable> getAllTables() {
        return tableDAO.findAll(0, Integer.MAX_VALUE);
    }

    public List<RestaurantTable> getAllTables(int offset, int limit) {
        return tableDAO.findAll(offset, limit);
    }

    public Optional<RestaurantTable> getTableById(Long id) {
        return tableDAO.findById(id);
    }

    public Optional<RestaurantTable> getByTableNo(int tableNo) {
        return tableDAO.findByTableNo(tableNo);
    }

    public List<RestaurantTable> getAvailableTables() {
        return tableDAO.findByStatus(TableStatus.EMPTY);
    }

    /* --------- Değişiklikler --------- */

    public Long createTable(int tableNo, String note) {
        RestaurantTable t = new RestaurantTable();
        t.setTableNo(tableNo);
        t.setStatus(TableStatus.EMPTY);
        t.setNote(note);
        Long id = tableDAO.create(t);
        t.setId(id);
        return id;
    }

    public void updateTable(RestaurantTable table) {
        tableDAO.update(table);
    }

    public void deleteTable(Long tableId) {
        tableDAO.deleteById(tableId);
    }

    public void markTableOccupied(Long tableId, boolean occupied) {
        TableStatus newStatus = occupied ? TableStatus.OCCUPIED : TableStatus.EMPTY;
        tableDAO.updateStatus(tableId, newStatus);
    }

    public void markTableReserved(Long tableId) {
        tableDAO.updateStatus(tableId, TableStatus.RESERVED);
    }

    public void updateTableNote(Long tableId, String note) {
        tableDAO.setNote(tableId, note);
    }
}
