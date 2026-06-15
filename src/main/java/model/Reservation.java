package model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Masa rezervasyonu — saat bazlı.
 *
 * <p>Şema {@code reservations} tablosuyla 1-1 eşleşir
 * (bkz. {@code SchemaPatcher#ensureReservationsTable}).
 */
public class Reservation {

    private Long id;
    private int tableNo;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String customerName;
    private String customerPhone;
    private int partySize = 1;
    private String notes;
    private ReservationStatus status = ReservationStatus.BOOKED;
    private LocalDateTime createdAt;
    private String createdBy;

    public Reservation() {}

    public Reservation(int tableNo, LocalDateTime startTime, LocalDateTime endTime,
                       String customerName, String customerPhone, int partySize,
                       String notes, String createdBy) {
        this.tableNo = tableNo;
        this.startTime = startTime;
        this.endTime = endTime;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.partySize = Math.max(1, partySize);
        this.notes = notes;
        this.createdBy = createdBy;
        this.status = ReservationStatus.BOOKED;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public int getTableNo() { return tableNo; }
    public void setTableNo(int tableNo) { this.tableNo = tableNo; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public int getPartySize() { return partySize; }
    public void setPartySize(int partySize) { this.partySize = Math.max(1, partySize); }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public ReservationStatus getStatus() { return status; }
    public void setStatus(ReservationStatus status) {
        this.status = status == null ? ReservationStatus.BOOKED : status;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Reservation that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
