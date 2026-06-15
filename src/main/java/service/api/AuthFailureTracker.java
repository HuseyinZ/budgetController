package service.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Başarısız login denemelerini takip eder ve brute-force koruması sağlar.
 *
 * <p>Anahtar: {@code username:ip} (her ikisi de küçük harfe normalize).
 * 5 ardışık başarısız deneme sonrasında 15 dakikalık geçici kilit devreye girer.
 * Başarılı login sayacı sıfırlar.
 *
 * <p>Loglara şifre/token YAZMAZ; kullanıcı adını {@link #maskUser(String)}
 * ile maskeleyerek WARN seviyesinde yazar.
 *
 * <p><b>TODO:</b> Üretimde dağıtık ortamlar için Redis/DB tabanlı backing
 * store gerekebilir. Şu an in-memory yeterlidir.
 */
public final class AuthFailureTracker {

    private static final Logger LOG = LoggerFactory.getLogger("auth.failure");

    private final int maxFailures;
    private final long lockoutMillis;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private ScheduledExecutorService janitor;

    public AuthFailureTracker() {
        this(5, TimeUnit.MINUTES.toMillis(15));
    }

    public AuthFailureTracker(int maxFailures, long lockoutMillis) {
        this.maxFailures = maxFailures;
        this.lockoutMillis = lockoutMillis;
        startJanitor();
    }

    /** Bu anahtarın kilidi açık mı? */
    public boolean isLocked(String username, String ip) {
        Counter c = counters.get(key(username, ip));
        if (c == null) return false;
        if (c.lockedUntil > System.currentTimeMillis()) return true;
        // Kilit süresi geçtiyse temizle
        if (c.failures >= maxFailures) {
            counters.remove(key(username, ip));
        }
        return false;
    }

    /**
     * Başarısız bir login denemesini kaydet. Eşik aşılırsa kilit devreye girer.
     * @return kalan deneme hakkı (negatifse kilit aktif)
     */
    public int recordFailure(String username, String ip) {
        String k = key(username, ip);
        long now = System.currentTimeMillis();
        Counter c = counters.compute(k, (key, prev) -> {
            if (prev == null) return new Counter(1, 0);
            return new Counter(prev.failures + 1, prev.lockedUntil);
        });
        // Eşiği aştıysa kilitle
        if (c.failures >= maxFailures && c.lockedUntil <= now) {
            counters.put(k, new Counter(c.failures, now + lockoutMillis));
            LOG.warn("auth.failure user={} ip={} attempt={} → kilit {} dk",
                    maskUser(username), maskIp(ip), c.failures,
                    TimeUnit.MILLISECONDS.toMinutes(lockoutMillis));
        } else {
            LOG.warn("auth.failure user={} ip={} attempt={}",
                    maskUser(username), maskIp(ip), c.failures);
        }
        return maxFailures - c.failures;
    }

    /** Başarılı giriş — sayacı temizle. */
    public void recordSuccess(String username, String ip) {
        counters.remove(key(username, ip));
    }

    /** Sızıntıyı önlemek için kullanıcı adı maskeleme. */
    private static String maskUser(String u) {
        if (u == null || u.isEmpty()) return "?";
        if (u.length() <= 2) return u.charAt(0) + "*";
        return u.charAt(0) + "***" + u.charAt(u.length() - 1);
    }

    /** IP adresi maskeleme (son oktet'i yıldızla). */
    private static String maskIp(String ip) {
        if (ip == null || ip.isEmpty()) return "?";
        int idx = ip.lastIndexOf('.');
        if (idx < 0) return ip;
        return ip.substring(0, idx) + ".*";
    }

    private static String key(String username, String ip) {
        return (Objects.toString(username, "?").toLowerCase()) + ":"
                + Objects.toString(ip, "?").toLowerCase();
    }

    public synchronized void shutdown() {
        if (janitor != null) {
            janitor.shutdownNow();
            janitor = null;
        }
        counters.clear();
    }

    private synchronized void startJanitor() {
        if (janitor != null) return;
        janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AuthFailureTracker-janitor");
            t.setDaemon(true);
            return t;
        });
        janitor.scheduleAtFixedRate(this::sweep, 5, 5, TimeUnit.MINUTES);
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        counters.entrySet().removeIf(e -> {
            Counter c = e.getValue();
            // Kilit süresi geçmiş ve eşik altı sayaçları sil.
            return c.lockedUntil < now && c.failures < maxFailures;
        });
    }

    private record Counter(int failures, long lockedUntil) {}
}
