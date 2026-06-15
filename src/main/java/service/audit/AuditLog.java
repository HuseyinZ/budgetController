package service.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.util.Mask;

/**
 * Güvenlik denetim log'u için tek noktadan yayın.
 *
 * <p>Tüm güvenlik-ilişkili olaylar bu sınıfın metodları üzerinden loglanır.
 * Logger adı {@code "audit"} → logback.xml'de ayrı bir appender'a yönlendirilebilir.
 *
 * <p><b>Maskeleme:</b> Kullanıcı adları {@link Mask#user(String)}, IP'ler son oktet
 * yıldızla maskelenir; şifre/token asla yazılmaz.
 *
 * <p>Olay isimlendirme şeması: {@code <alan>.<olay>} (örn. {@code auth.success},
 * {@code user.role.changed}). SIEM'e ileride göndermek için bu sade format kolay parse edilir.
 */
public final class AuditLog {

    private static final Logger LOG = LoggerFactory.getLogger("audit");

    private AuditLog() {}

    // ============================================================
    //   Kimlik doğrulama olayları
    // ============================================================

    public static void authSuccess(String username, String ip) {
        LOG.info("auth.success user={} ip={}", Mask.user(username), maskIp(ip));
    }

    public static void authFailure(String username, String ip, String reason) {
        // NOT: AuthFailureTracker da kendi logger'ında ("auth.failure") detay yazar.
        // Burası tek satırlık özet — SIEM agregasyonu için.
        LOG.warn("auth.failure user={} ip={} reason={}",
                Mask.user(username), maskIp(ip), reason == null ? "?" : reason);
    }

    public static void logout(String username) {
        LOG.info("auth.logout user={}", Mask.user(username));
    }

    public static void sessionTimeoutIdle(String username) {
        LOG.info("auth.session.idle user={}", Mask.user(username));
    }

    // ============================================================
    //   Kullanıcı / yetki yönetimi
    // ============================================================

    public static void passwordChanged(String actor, String targetUser) {
        LOG.info("user.password.changed actor={} target={}",
                Mask.user(actor), Mask.user(targetUser));
    }

    public static void userCreated(String actor, String username, String role) {
        LOG.info("user.created actor={} target={} role={}",
                Mask.user(actor), Mask.user(username), role);
    }

    public static void userDeleted(String actor, String targetUser) {
        LOG.info("user.deleted actor={} target={}",
                Mask.user(actor), Mask.user(targetUser));
    }

    public static void userActivated(String actor, String targetUser, boolean active) {
        LOG.info("user.activated actor={} target={} active={}",
                Mask.user(actor), Mask.user(targetUser), active);
    }

    public static void roleChanged(String actor, String targetUser, String oldRole, String newRole) {
        LOG.info("user.role.changed actor={} target={} from={} to={}",
                Mask.user(actor), Mask.user(targetUser), oldRole, newRole);
    }

    // ============================================================
    //   POS işlemleri
    // ============================================================

    public static void productDeleted(String actor, long productId, String name) {
        LOG.info("product.deleted actor={} productId={} name={}",
                Mask.user(actor), productId, name == null ? "?" : name);
    }

    public static void priceChanged(String actor, long productId, String oldPrice, String newPrice) {
        LOG.info("product.price.changed actor={} productId={} from={} to={}",
                Mask.user(actor), productId, oldPrice, newPrice);
    }

    public static void refundIssued(String actor, long orderId, String amount, String reason) {
        LOG.info("payment.refund actor={} orderId={} amount={} reason={}",
                Mask.user(actor), orderId, amount, reason == null ? "?" : reason);
    }

    public static void orderCancelled(String actor, long orderId, String reason) {
        LOG.info("order.cancelled actor={} orderId={} reason={}",
                Mask.user(actor), orderId, reason == null ? "?" : reason);
    }

    // ============================================================
    //   Sistem / yedek
    // ============================================================

    public static void backupCreated(String fileName, long sizeBytes, boolean encrypted) {
        LOG.info("backup.created file={} size={} encrypted={}",
                fileName, sizeBytes, encrypted);
    }

    public static void backupRestoreAttempt(String actor, String fileName) {
        LOG.warn("backup.restore.attempt actor={} file={}",
                Mask.user(actor), fileName);
    }

    public static void settingsChanged(String actor, String key) {
        LOG.info("settings.changed actor={} key={}", Mask.user(actor), key);
    }

    // ============================================================
    //   Yardımcı
    // ============================================================

    private static String maskIp(String ip) {
        if (ip == null || ip.isEmpty()) return "?";
        int idx = ip.lastIndexOf('.');
        if (idx < 0) return ip;
        return ip.substring(0, idx) + ".*";
    }
}
