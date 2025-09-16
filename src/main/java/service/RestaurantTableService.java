package service;

import dao.RestaurantTableDAO;
import dao.jdbc.RestaurantTableJdbcDAO;
import model.RestaurantTable;
import model.TableStatus;

import java.util.List;
import java.util.Optional;

public class RestaurantTableService {

    private final RestaurantTableDAO tableDAO = new RestaurantTableJdbcDAO();

    /* --------- Sorgular --------- */

    /** Tüm masaları getir (no-arg sürüm; arka planda geniş limit veriyoruz). */
    public List<RestaurantTable> getAllTables() {
        return tableDAO.findAll(0, Integer.MAX_VALUE);
    }

    /** Sayfalı listeleme istersen. */
    public List<RestaurantTable> getAllTables(int offset, int limit) {
        return tableDAO.findAll(offset, limit);
    }

    public Optional<RestaurantTable> getTableById(Long id) {
        return tableDAO.findById(id);
    }

    public Optional<RestaurantTable> getByTableNo(int tableNo) {
        return tableDAO.findByTableNo(tableNo);
    }

    /** Şu anda boş olanlar. */
    public List<RestaurantTable> getAvailableTables() {
        return tableDAO.findByStatus(TableStatus.EMPTY);
    }

    /* --------- Değişiklikler --------- */

    /** Yeni masa oluştur. */
    public Long createTable(int tableNo, String note) {
        RestaurantTable t = new RestaurantTable();
        t.setTableNo(tableNo);
        t.setStatus(TableStatus.EMPTY);
        t.setNote(note);
        Long id = tableDAO.create(t);
        t.setId(id);
        return id;
    }

    /** Masa bilgilerini güncelle. */
    public void updateTable(RestaurantTable table) {
        tableDAO.update(table); // void
    }

    /** Masayı sil. */
    public void deleteTable(Long tableId) {
        tableDAO.deleteById(tableId); // void
    }

    /** Masayı dolu/boş işaretle. */
    public void markTableOccupied(Long tableId, boolean occupied) {
        TableStatus newStatus = occupied ? TableStatus.OCCUPIED : TableStatus.EMPTY;
        tableDAO.updateStatus(tableId, newStatus); // void
    }

    /** Masa notu güncelle. */
    public void updateTableNote(Long tableId, String note) {
        tableDAO.setNote(tableId, note); // void
    }
}
