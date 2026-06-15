package service.api;

import model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * REST API için sunucu-yan oturum (token) deposu.
 *
 * <p><b>Tasarım:</b>
 * <ul>
 *   <li>Login başarılı olunca rastgele 32 byte (256-bit) opak token üretilir.</li>
 *   <li>Token in-memory {@link ConcurrentHashMap}'te saklanır.</li>
 *   <li>Her token'ın TTL'i vardır (varsayılan 8 saat); arka plan thread'i
 *       süresi geçmişleri temizler.</li>
 *   <li>Token doğrulama {@link #lookup(String)} ile O(1).</li>
 *   <li>Token logger'a YAZILMAZ — kabul / red işlemleri sadece kullanıcı adı ile loglanır.</li>
 * </ul>
 *
 * <p><b>TODO:</b> Üretimde tek bir JVM birden fazla instance'a ölçeklenirse
 * (örn. cluster), token store'u Redis/DB'ye taşımak gerekir. Şu an
 * uygulama tek-process olduğu için in-memory yeterlidir.
 */
public final class SessionStore {

    private static final Logger LOG = LoggerFactory.getLogger(SessionStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final long absoluteTtlMillis;
    private final long idleTtlMillis;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private ScheduledExecutorService janitor;

    public SessionStore() {
        this(TimeUnit.HOURS.toMillis(8), TimeUnit.MINUTES.toMillis(30));
    }

    /**
     * Geri uyumlu yapıcı — sadece absolute TTL. Idle timeout devre dışı (0).
     * @deprecated yeni kodlar için iki argümanlı yapıcıyı kullanın.
     */
    @Deprecated
    public SessionStore(long ttlMillis) {
        this(ttlMillis, 0L);
    }

    /**
     * @param absoluteTtlMillis Token'ın oluşturulduğu andan itibaren mutlak yaşam süresi.
     *                          (Çalınsa bile en fazla bu kadar süre kullanılabilir.)
     * @param idleTtlMillis     Son erişimden bu kadar süre geçerse token düşer.
     *                          0 → idle timeout devre dışı.
     */
    public SessionStore(long absoluteTtlMillis, long idleTtlMillis) {
        this.absoluteTtlMillis = absoluteTtlMillis;
        this.idleTtlMillis = idleTtlMillis;
        startJanitor();
    }

    /** Bir kullanıcı için yeni token üretip kaydeder. */
    public String issue(User user) {
        Objects.requireNonNull(user, "user");
        String token = newToken();
        long now = System.currentTimeMillis();
        long expiresAt = now + absoluteTtlMillis;
        sessions.put(token, new Session(user, expiresAt, now));
        // Token loglanmaz; sadece kullanıcı adı.
        LOG.debug("Oturum açıldı: user={}", user.getUsername());
        return token;
    }

    /**
     * Token geçerli mi? Geçerliyse {@link User} dön, değilse boş.
     *
     * <p>İki ayrı süre kontrolü:
     * <ul>
     *   <li>Absolute timeout: {@code issue()} anından bu yana geçen süre.</li>
     *   <li>Idle timeout: son {@code lookup()} (= aktif kullanım) anından bu yana geçen süre.</li>
     * </ul>
     * Geçerli lookup, idle sayacını sıfırlar (lastAccess güncellenir).
     */
    public java.util.Optional<User> lookup(String token) {
        if (token == null || token.isEmpty()) return java.util.Optional.empty();
        Session s = sessions.get(token);
        if (s == null) return java.util.Optional.empty();
        long now = System.currentTimeMillis();
        // 1) Absolute timeout
        if (s.expiresAt < now) {
            sessions.remove(token);
            return java.util.Optional.empty();
        }
        // 2) Idle timeout (0 = devre dışı)
        if (idleTtlMillis > 0 && (now - s.lastAccessAt) > idleTtlMillis) {
            sessions.remove(token);
            LOG.debug("Oturum idle timeout: user={}", s.user.getUsername());
            return java.util.Optional.empty();
        }
        // Aktif kullanım → lastAccess güncelle (yeni Session record üret)
        sessions.put(token, new Session(s.user, s.expiresAt, now));
        return java.util.Optional.of(s.user);
    }

    /** Token'ı iptal et (logout). */
    public void revoke(String token) {
        if (token == null || token.isEmpty()) return;
        Session s = sessions.remove(token);
        if (s != null) {
            LOG.debug("Oturum kapatıldı: user={}", s.user.getUsername());
        }
    }

    /** Sabit zamanlı string karşılaştırma — timing attack azaltma. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    public int size() { return sessions.size(); }

    public synchronized void shutdown() {
        if (janitor != null) {
            janitor.shutdownNow();
            janitor = null;
        }
        sessions.clear();
    }

    private String newToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private synchronized void startJanitor() {
        if (janitor != null) return;
        janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SessionStore-janitor");
            t.setDaemon(true);
            return t;
        });
        // 5 dakikada bir süresi dolmuşları temizle.
        janitor.scheduleAtFixedRate(this::sweepExpired, 5, 5, TimeUnit.MINUTES);
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            Map.Entry<String, Session> e = it.next();
            Session s = e.getValue();
            boolean expired = s.expiresAt < now
                    || (idleTtlMillis > 0 && (now - s.lastAccessAt) > idleTtlMillis);
            if (expired) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) LOG.debug("Süresi geçmiş {} oturum temizlendi", removed);
    }

    /** Oturum kaydı: kullanıcı + mutlak son tarih + son erişim zamanı. */
    private record Session(User user, long expiresAt, long lastAccessAt) {}
}
