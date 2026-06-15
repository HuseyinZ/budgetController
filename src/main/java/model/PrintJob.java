package model;

import java.time.LocalDateTime;

/**
 * Veritabanı tablo karşılığı: print_jobs
 *
 * <p>Bir fişin gönderim girişiminin kalıcı kaydıdır. Yazıcı kapalı,
 * kağıt sıkışmış ya da ağ çöktüğünde {@link PrintJobStatus#FAILED}
 * olur; arka plan worker bunları tekrar dener.
 */
public class PrintJob extends BaseEntity {

    private Long orderId;
    private Integer printerId;
    private String payload;              // serileştirilmiş Receipt JSON
    private PrintJobStatus status = PrintJobStatus.PENDING;
    private int attempts = 0;
    private String lastError;
    private LocalDateTime printedAt;

    public Long getOrderId()                    { return orderId; }
    public void setOrderId(Long orderId)        { this.orderId = orderId; }

    public Integer getPrinterId()               { return printerId; }
    public void setPrinterId(Integer printerId) { this.printerId = printerId; }

    public String getPayload()                  { return payload; }
    public void setPayload(String payload)      { this.payload = payload; }

    public PrintJobStatus getStatus()           { return status; }
    public void setStatus(PrintJobStatus s)     { this.status = s; }

    public int getAttempts()                    { return attempts; }
    public void setAttempts(int attempts)       { this.attempts = attempts; }

    public String getLastError()                { return lastError; }
    public void setLastError(String lastError)  { this.lastError = lastError; }

    public LocalDateTime getPrintedAt()         { return printedAt; }
    public void setPrintedAt(LocalDateTime p)   { this.printedAt = p; }

    public enum PrintJobStatus { PENDING, PRINTED, FAILED }
}
