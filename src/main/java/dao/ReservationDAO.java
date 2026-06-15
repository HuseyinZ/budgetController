package dao;

import model.Reservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Masa rezervasyonu DAO arayüzü.
 */
public interface ReservationDAO extends CrudRepository<Reservation, Long> {

    /** Belirli bir gün (00:00 .. ertesi 00:00) için tüm rezervasyonlar (her statüden). */
    List<Reservation> findByDate(LocalDate date);

    /** Verilen tarih aralığındaki rezervasyonlar — UI listeleme için. */
    List<Reservation> findBetween(LocalDateTime startInclusive, LocalDateTime endExclusive);

    /** Verilen masada, verilen zaman aralığıyla ÇAKIŞAN (CANCELLED hariç) rezervasyonlar. */
    List<Reservation> findOverlapping(int tableNo, LocalDateTime start, LocalDateTime end, Long ignoreId);

    /** Verilen masanın "yaklaşan" (status=BOOKED, end_time > now) ilk rezervasyonu. */
    Optional<Reservation> findNextForTable(int tableNo);

    /** Tüm masalardaki yaklaşan rezervasyonların özeti — tables view'da chip için. */
    List<Reservation> findUpcoming(LocalDateTime from, int limitPerTable);

    /** Status güncelle. */
    void updateStatus(long id, model.ReservationStatus status);
}
