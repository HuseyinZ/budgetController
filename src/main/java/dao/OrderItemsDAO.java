package dao;

import model.ItemNoteUpdateResult;
import model.OrderItem;

import java.math.BigDecimal;
import java.util.List;

public interface OrderItemsDAO extends CrudRepository<OrderItem, Long> {
    List<OrderItem> findByOrderId(Long orderId);
    void addOrIncrement(Long orderId, Long productId, String productName, int qty, BigDecimal unitPrice);

    /**
     * addOrIncrement'in şiş/porsiyon snapshot'larını destekleyen overload'ı.
     * <p>{@code piecesPerPortion} ve {@code unitLabel} ürünün sipariş anındaki
     * porsiyonlama ayarlarıdır; mevcut satır varsa override edilmez (zaten
     * snapshot'tır), yoksa INSERT'e dahil edilir.
     */
    void addOrIncrement(Long orderId, Long productId, String productName, int qty,
                        BigDecimal unitPrice, Integer piecesPerPortion, String unitLabel);
    void decrementOrRemove(Long orderItemId, int qty);
    void removeAllForOrder(Long orderId);

    /**
     * Garson satır bazlı mutfak override'ı atadığında.
     * {@code printerId == null} ise override kaldırılır.
     */
    void updateKitchenOverride(Long orderItemId, Integer printerId);

    /**
     * Mutfağa basılmış kalemleri {@code printed_at} ile işaretler ve
     * {@code print_count}'u 1 arttırır. Sadece henüz basılmamış (printed_at IS NULL)
     * satırlar etkilenir; idempotent.
     *
     * @return işaretlenmiş satır sayısı
     */
    int markItemsPrinted(Long orderId);

    /**
     * Bir kalemin notunu günceller. {@code note == null} → notu temizle.
     *
     * @return sonucun sınıflandırması — {@link ItemNoteUpdateResult#APPLIED} yalnızca
     *         UPDATE gerçekten bir satırla eşleştiğinde döner.
     */
    ItemNoteUpdateResult updateNote(Long orderItemId, String note);
}
