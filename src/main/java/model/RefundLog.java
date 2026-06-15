package model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Bir iade / iptal / silme işleminin denetim (audit) kaydı.
 *
 * <p>Şu işlemler kayıt altına alınır:
 * <ul>
 *   <li>{@link ActionType#DECREASE_ITEM} — bir kalemin adedi azaltıldı</li>
 *   <li>{@link ActionType#REMOVE_ITEM}   — bir kalem tamamen silindi</li>
 *   <li>{@link ActionType#CLEAR_TABLE}   — masa tamamen temizlendi</li>
 *   <li>{@link ActionType#CANCEL_ORDER}  — sipariş iptal edildi (ileride)</li>
 * </ul>
 *
 * <p>Bu tablo SADECE eklemeye açıktır (immutable history); düzenlenmez,
 * silinmez. Şüpheli durumda admin {@link UI.RefundHistoryPanel} üzerinden
 * filtreleyerek inceler.
 */
public class RefundLog extends BaseEntity {

    public enum ActionType {
        DECREASE_ITEM,
        REMOVE_ITEM,
        CLEAR_TABLE,
        CANCEL_ORDER
    }

    private Long userId;            // İşlemi yapan kullanıcı (kasiyer/admin)
    private String userName;        // Cache amaçlı — kullanıcı sonradan silinse de okunsun
    private ActionType actionType;
    private Integer tableNo;        // Hangi masa
    private Long orderId;           // Hangi sipariş (null olabilir)
    private String productName;     // Hangi ürün (null = clearTable gibi tüm masa)
    private Integer quantity;       // Kaç adet (null = clear)
    private BigDecimal amount;      // İade tutarı (₺)
    private String reason;          // Kullanıcının girdiği neden (zorunlu)
    private LocalDateTime createdAt;

    public RefundLog() {}

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public ActionType getActionType() { return actionType; }
    public void setActionType(ActionType actionType) { this.actionType = actionType; }

    public Integer getTableNo() { return tableNo; }
    public void setTableNo(Integer tableNo) { this.tableNo = tableNo; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
