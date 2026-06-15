package service.print;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Tek bir fişin makinaya bağımsız temsili.
 * Bir Receipt nesnesi → bir kağıt çıktı.
 *
 * <p>Mutfak fişlerinde:
 * <ul>
 *   <li>{@link #getSalonName()} + {@link #getTableNo()} fişin en üstünde
 *       büyük fontla basılır.</li>
 *   <li>{@link #getLines()} listesindeki her kalemin
 *       {@link Line#isHighlighted()} alanı varsa, o satır <b>kalın/büyük</b>;
 *       değilse <i>küçük font ve "BİLGİ"</i> şeklinde basılır.</li>
 * </ul>
 *
 * <p>Bu sayede aynı sipariş bütün mutfaklara gönderilebilir ve her mutfak
 * kendi kalemlerini vurgulu, diğerlerini bilgi amaçlı görür.
 */
public final class Receipt {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final String header;          // Örn: "*** OCAK ***"
    private final String salonName;       // Örn: "Salon A" / "Üst Kat"
    private final String tableNo;
    private final String waiterName;
    private final LocalDateTime time;
    private final List<Line> lines;
    private final String orderNote;
    private final Long orderId;

    public Receipt(String header,
                   String salonName,
                   String tableNo,
                   String waiterName,
                   LocalDateTime time,
                   List<Line> lines,
                   String orderNote,
                   Long orderId) {
        this.header = Objects.requireNonNullElse(header, "");
        this.salonName = Objects.requireNonNullElse(salonName, "");
        this.tableNo = Objects.requireNonNullElse(tableNo, "-");
        this.waiterName = Objects.requireNonNullElse(waiterName, "-");
        this.time = Objects.requireNonNullElse(time, LocalDateTime.now());
        this.lines = Collections.unmodifiableList(Objects.requireNonNull(lines, "lines"));
        this.orderNote = orderNote;
        this.orderId = orderId;
    }

    public String getHeader()           { return header; }
    public String getSalonName()        { return salonName; }
    public String getTableNo()          { return tableNo; }
    public String getWaiterName()       { return waiterName; }
    public LocalDateTime getTime()      { return time; }
    public List<Line> getLines()        { return lines; }
    public String getOrderNote()        { return orderNote; }
    public Long getOrderId()            { return orderId; }
    public String getTimeFormatted()    { return time.format(TS_FMT); }

    public boolean isEmpty()            { return lines.isEmpty(); }

    /** Tek bir kalem (örn: 2× Kuzu Ciğer). */
    public static final class Line {
        private final int quantity;
        private final String productName;
        private final String note;
        /**
         * Bu satır <b>bu mutfağın hazırlayacağı</b> bir kalem mi?
         * true → kalın/büyük font; false → küçük "bilgi" satırı.
         */
        private final boolean highlighted;

        public Line(int quantity, String productName, String note, boolean highlighted) {
            if (quantity <= 0) {
                throw new IllegalArgumentException("quantity > 0 olmalı");
            }
            this.quantity = quantity;
            this.productName = Objects.requireNonNullElse(productName, "?");
            this.note = note;
            this.highlighted = highlighted;
        }

        public Line(int quantity, String productName, String note) {
            this(quantity, productName, note, true);
        }

        public Line(int quantity, String productName) {
            this(quantity, productName, null, true);
        }

        public int getQuantity()       { return quantity; }
        public String getProductName() { return productName; }
        public String getNote()        { return note; }
        public boolean isHighlighted() { return highlighted; }

        @Override
        public String toString() {
            return (highlighted ? "" : "(bilgi) ") + quantity + "x " + productName
                    + (note == null ? "" : " — " + note);
        }
    }

    @Override
    public String toString() {
        return "Receipt{" +
                "header='" + header + '\'' +
                ", salon='" + salonName + '\'' +
                ", table=" + tableNo +
                ", waiter=" + waiterName +
                ", time=" + getTimeFormatted() +
                ", lines=" + lines.size() +
                ", orderId=" + orderId +
                '}';
    }
}
