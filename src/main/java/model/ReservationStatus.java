package model;

/**
 * Bir masa rezervasyonunun durumu.
 *
 * <ul>
 *   <li>{@link #BOOKED}   — kayıt aktif, müşteri henüz gelmedi</li>
 *   <li>{@link #SEATED}   — müşteri geldi, masaya oturdu</li>
 *   <li>{@link #CANCELLED}— iptal edildi</li>
 *   <li>{@link #NO_SHOW}  — müşteri gelmedi (zamanı geçti)</li>
 * </ul>
 */
public enum ReservationStatus {
    BOOKED,
    SEATED,
    CANCELLED,
    NO_SHOW;

    /** Liste/karşılaştırma için güvenli parse. Tanınmayan değerlerde {@link #BOOKED}. */
    public static ReservationStatus parse(String raw) {
        if (raw == null) return BOOKED;
        try {
            return ReservationStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return BOOKED;
        }
    }
}
