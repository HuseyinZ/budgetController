package service;

import dao.ReservationDAO;
import dao.jdbc.ReservationJdbcDAO;
import model.Reservation;
import model.ReservationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Masa rezervasyonu iş katmanı — validasyon + çakışma kontrolü.
 */
public class ReservationService {

    private static final Logger LOG = LoggerFactory.getLogger(ReservationService.class);

    private static final Duration MIN_DURATION = Duration.ofMinutes(15);
    private static final Duration MAX_DURATION = Duration.ofHours(8);

    private final ReservationDAO dao;

    public ReservationService() { this(new ReservationJdbcDAO()); }

    public ReservationService(ReservationDAO dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    /**
     * Yeni rezervasyon oluştur — çakışma yoksa eklenir, varsa
     * {@link IllegalStateException} fırlatır.
     */
    public Reservation create(int tableNo, LocalDateTime start, LocalDateTime end,
                              String customerName, String customerPhone, int partySize,
                              String notes, String createdBy) {
        validate(tableNo, start, end, customerName);
        List<Reservation> overlap = dao.findOverlapping(tableNo, start, end, null);
        if (!overlap.isEmpty()) {
            Reservation o = overlap.get(0);
            throw new IllegalStateException("Bu masada bu saatler için zaten rezervasyon var: " +
                    o.getCustomerName() + " — " + o.getStartTime() + " / " + o.getEndTime());
        }
        Reservation r = new Reservation(tableNo, start, end, customerName, customerPhone,
                partySize, notes, createdBy);
        Long id = dao.create(r);
        r.setId(id);
        LOG.info("Rezervasyon eklendi: id={}, masa={}, {}", id, tableNo, customerName);
        return r;
    }

    /** İptal — kaydı silmez, statüyü CANCELLED yapar. */
    public void cancel(long id) {
        dao.updateStatus(id, ReservationStatus.CANCELLED);
    }

    /** "Müşteri geldi, oturdu" — statüyü SEATED yapar. */
    public void markSeated(long id) {
        dao.updateStatus(id, ReservationStatus.SEATED);
    }

    /** "Müşteri gelmedi" — statüyü NO_SHOW yapar. */
    public void markNoShow(long id) {
        dao.updateStatus(id, ReservationStatus.NO_SHOW);
    }

    public Optional<Reservation> findById(long id) {
        return dao.findById(id);
    }

    /** Belirli bir günün tüm rezervasyonları (statüsüne bakmaz). */
    public List<Reservation> listForDate(LocalDate date) {
        return dao.findByDate(date);
    }

    /** Masa kartında "🕒 19:30 — Ahmet" rozeti için: yaklaşan ilk rezervasyon. */
    public Optional<Reservation> nextForTable(int tableNo) {
        return dao.findNextForTable(tableNo);
    }

    /** Tüm yaklaşan rezervasyonlar (UI yüksek seviyeli görünüm). */
    public List<Reservation> upcoming() {
        return dao.findUpcoming(LocalDateTime.now(), 50);
    }

    // -------------------------------------------------------

    private void validate(int tableNo, LocalDateTime start, LocalDateTime end, String customer) {
        if (tableNo <= 0) {
            throw new IllegalArgumentException("Geçersiz masa no");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("Başlangıç/bitiş saati gerekli");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("Bitiş saati başlangıçtan sonra olmalı");
        }
        Duration d = Duration.between(start, end);
        if (d.compareTo(MIN_DURATION) < 0) {
            throw new IllegalArgumentException("Rezervasyon en az " + MIN_DURATION.toMinutes() + " dakika olmalı");
        }
        if (d.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("Rezervasyon en fazla " + MAX_DURATION.toHours() + " saat olabilir");
        }
        if (customer == null || customer.isBlank()) {
            throw new IllegalArgumentException("Müşteri adı gerekli");
        }
    }
}
