package service.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Basit token-bucket rate limiter (in-memory).
 *
 * <p>Endpoint başına ayrı bucket grubu; her bucket anahtarı genelde
 * {@code (kullanıcı:IP)}. Bucket dakikada {@code refillPerMin} kadar token
 * toplar; istek geldiğinde token tüketilir. Token tükenirse 429.
 *
 * <p>Kullanım:
 * <pre>
 * RateLimiter adminRl = new RateLimiter("admin", 30);   // 30 req/dk
 * if (!adminRl.tryAcquire(user.username + ":" + ip)) {
 *     ctx.status(429).json(Map.of("error", "rate_limited"));
 *     return;
 * }
 * </pre>
 *
 * <p><b>TODO:</b> Distributed deployment'a geçilirse Redis-tabanlı versiyona
 * göç edilmeli. Şu an tek-process için yeterli.
 */
public class RateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger("rate.limit");

    private final String name;
    private final int capacity;
    private final long refillIntervalNanos;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private ScheduledExecutorService janitor;

    /**
     * @param name           Logging için ayırt edici ad (örn. "login", "admin").
     * @param refillPerMin   Dakikada eklenen token sayısı (= max kalıcı oran).
     */
    public RateLimiter(String name, int refillPerMin) {
        this(name, refillPerMin, refillPerMin); // burst = sustained
    }

    /**
     * @param name           Logging için ayırt edici ad.
     * @param refillPerMin   Dakikada eklenen token sayısı.
     * @param burstCapacity  Bucket maksimum kapasitesi (anlık burst limiti).
     */
    public RateLimiter(String name, int refillPerMin, int burstCapacity) {
        if (refillPerMin <= 0) throw new IllegalArgumentException("refillPerMin > 0 olmalı");
        if (burstCapacity <= 0) throw new IllegalArgumentException("burstCapacity > 0 olmalı");
        this.name = name;
        this.capacity = burstCapacity;
        this.refillIntervalNanos = TimeUnit.MINUTES.toNanos(1) / refillPerMin;
        startJanitor();
    }

    /** Bir token tüket — başarılıysa true, kapasite yoksa false. */
    public boolean tryAcquire(String key) {
        if (key == null || key.isEmpty()) key = "?";
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        return b.tryAcquire(capacity, refillIntervalNanos);
    }

    /** Belirli bir anahtarın limitini sıfırla (testler veya manuel müdahale). */
    public void reset(String key) {
        if (key != null) buckets.remove(key);
    }

    public synchronized void shutdown() {
        if (janitor != null) {
            janitor.shutdownNow();
            janitor = null;
        }
        buckets.clear();
    }

    private synchronized void startJanitor() {
        if (janitor != null) return;
        janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RateLimiter-" + name + "-janitor");
            t.setDaemon(true);
            return t;
        });
        // 10 dakikadır kullanılmamış bucket'ları temizle
        janitor.scheduleAtFixedRate(this::sweepIdle, 10, 10, TimeUnit.MINUTES);
    }

    private void sweepIdle() {
        long now = System.nanoTime();
        long idleThreshold = TimeUnit.MINUTES.toNanos(10);
        buckets.entrySet().removeIf(e -> (now - e.getValue().lastTouchNanos.get()) > idleThreshold);
    }

    /** Atomik token bucket. */
    private static final class Bucket {
        private final AtomicLong tokens;
        private final AtomicLong lastRefillNanos;
        private final AtomicLong lastTouchNanos;

        Bucket(int initialTokens) {
            long now = System.nanoTime();
            this.tokens = new AtomicLong(initialTokens);
            this.lastRefillNanos = new AtomicLong(now);
            this.lastTouchNanos = new AtomicLong(now);
        }

        boolean tryAcquire(int capacity, long refillIntervalNanos) {
            long now = System.nanoTime();
            lastTouchNanos.set(now);
            // Refill: kaç tam refill geçtiği kadar token ekle
            long last = lastRefillNanos.get();
            long elapsed = now - last;
            if (elapsed >= refillIntervalNanos) {
                long add = elapsed / refillIntervalNanos;
                if (lastRefillNanos.compareAndSet(last, last + add * refillIntervalNanos)) {
                    long current = tokens.get();
                    long updated = Math.min(capacity, current + add);
                    tokens.set(updated);
                }
            }
            // Tüket
            while (true) {
                long current = tokens.get();
                if (current <= 0) return false;
                if (tokens.compareAndSet(current, current - 1)) return true;
            }
        }
    }
}
