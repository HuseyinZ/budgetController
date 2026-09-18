package service.db;

import DataConnection.Db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Locale;

/**
 * Açılışta veritabanının erişilebilir hale gelmesini bekler (C2).
 *
 * <p>Neden var: Windows'ta uygulama, MySQL servisi henüz ayağa kalkmadan
 * başlayabilir. Eski davranışta bu, açılışta sert bir hataydı. Artık havuz
 * tembel kurulur ({@link Db}) ve burada sınırlı bir süre boyunca yeniden
 * denenir.
 *
 * <p>Tasarım kuralları:
 * <ul>
 *   <li>Yeni havuz açılmaz; var olan {@link Db} havuzu üzerinden yoklanır.</li>
 *   <li>Hiçbir DDL/DML çalıştırılmaz — yalnız bağlantı alınıp kapatılır.</li>
 *   <li>{@code System.exit} çağrılmaz; yapılandırılmış bir {@link Result} döner.</li>
 *   <li>Yapılandırma/programlama hataları 90 saniye boyunca körlemesine
 *       yeniden denenmez; ilk denemede ayrılır.</li>
 *   <li>Log'a yalnız sınıf adı, SQLState ve vendorCode yazılır — exception
 *       mesajı, stack trace, JDBC URL veya credential ASLA yazılmaz.</li>
 *   <li>Zaman/uyku/yoklama enjekte edilebilir; testler gerçekten uyumaz.</li>
 * </ul>
 */
public final class StartupHealth {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(StartupHealth.class);

    /** Bekleme sırasında kullanıcıya gösterilen metin. */
    public static final String MESSAGE_WAITING = "Veritabanı bekleniyor…";

    /** Süre dolduğunda kullanıcıya gösterilen metin — teknik ayrıntı içermez. */
    public static final String MESSAGE_TIMEOUT =
            "Veritabanı servisi çalışmıyor — yönetici: MySQL servisini başlatın";

    /** Yeniden denemenin anlamsız olduğu yapılandırma hatası metni. */
    public static final String MESSAGE_CONFIG =
            "Veritabanı bağlantı ayarları eksik veya hatalı — yönetici: yapılandırmayı kontrol edin";

    public static final long DEFAULT_BUDGET_MS = 90_000L;
    public static final long DEFAULT_INTERVAL_MS = 3_000L;

    public enum Outcome {
        /** Bağlantı alındı. */
        AVAILABLE,
        /** Süre doldu; DB hâlâ erişilemez. */
        TIMED_OUT,
        /** Yeniden denemekle düzelmeyecek hata (yapılandırma/yetki/veritabanı adı). */
        NOT_RETRYABLE
    }

    /**
     * @param outcome      sonuç
     * @param attempts     yapılan yoklama sayısı
     * @param waitedMillis toplam beklenen süre
     * @param userMessage  kullanıcıya gösterilebilecek metin (teknik ayrıntısız); başarıda {@code null}
     */
    public record Result(Outcome outcome, int attempts, long waitedMillis, String userMessage) {
        public boolean available() {
            return outcome == Outcome.AVAILABLE;
        }
    }

    /** Tek bir erişilebilirlik yoklaması. Başarısızlıkta istisna fırlatır. */
    @FunctionalInterface
    public interface Probe {
        void probe() throws Exception;
    }

    /** Uyku — testte sahte saat ilerletmek için. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Monotonik zaman kaynağı — testte sahte saat. */
    @FunctionalInterface
    public interface Clock {
        long nowMillis();
    }

    private final long budgetMillis;
    private final long intervalMillis;
    private final Probe probe;
    private final Sleeper sleeper;
    private final Clock clock;

    StartupHealth(long budgetMillis, long intervalMillis, Probe probe, Sleeper sleeper, Clock clock) {
        if (budgetMillis <= 0 || intervalMillis <= 0) {
            throw new IllegalArgumentException("budget ve interval pozitif olmalı");
        }
        this.budgetMillis = budgetMillis;
        this.intervalMillis = intervalMillis;
        this.probe = probe;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** Üretim yapılandırması: 90 saniye boyunca 3 saniyede bir, uygulama havuzu üzerinden. */
    public static StartupHealth production() {
        return new StartupHealth(DEFAULT_BUDGET_MS, DEFAULT_INTERVAL_MS,
                StartupHealth::probeApplicationPool, Thread::sleep, StartupHealth::monotonicMillis);
    }

    /**
     * Var olan uygulama havuzundan bir bağlantı alır ve hemen kapatır.
     * Hiçbir sorgu çalıştırılmaz.
     */
    static void probeApplicationPool() throws SQLException {
        try (Connection c = Db.getConnection()) {
            if (c == null) {
                throw new SQLException("no connection", "08003");
            }
        }
    }

    /**
     * Veritabanı erişilebilir olana kadar bekler.
     *
     * @return sonuç; {@link Outcome#AVAILABLE} ise çağıran devam edebilir
     */
    public Result await() {
        long start = clock.nowMillis();
        long deadline = start + budgetMillis;
        int attempts = 0;
        Throwable last = null;

        while (true) {
            // Tempo deneme BAŞLANGICINDAN başlangıcına ölçülür. Yoklamanın kendisi
            // Hikari connectionTimeout'u kadar (~2,5 sn) sürebildiğinden, her
            // başarısızlıktan sonra sabit 3 sn uyumak temposu ~5,5 sn'ye çıkarırdı.
            long attemptStart = clock.nowMillis();
            // Her yoklamadan ÖNCE saate bakılır: uzun süren bir yoklama süre
            // bütçesini kendi başına aştıysa yeni yoklama başlatılmaz.
            if (attemptStart >= deadline) {
                LOG.error("Database unavailable after {} attempt(s) in {} ms ({}).",
                        attempts, attemptStart - start, describe(last));
                return new Result(Outcome.TIMED_OUT, attempts, attemptStart - start, MESSAGE_TIMEOUT);
            }
            attempts++;
            try {
                probe.probe();
                long waited = clock.nowMillis() - start;
                if (attempts > 1) {
                    LOG.info("Database became available after {} attempt(s), {} ms.", attempts, waited);
                }
                return new Result(Outcome.AVAILABLE, attempts, waited, null);
            } catch (Exception | ExceptionInInitializerError t) {
                // Yalnız normal istisnalar ve tembel Db kurulumunun üretebileceği
                // başlatma hatası ele alınır. AssertionError, OutOfMemoryError gibi
                // beklenmeyen JVM/programlama hataları YUTULMAZ, yukarı yayılır.
                //
                // NOT: ExceptionInInitializerError yalnız ilk sınıf başlatma
                // denemesinde oluşur. Db kurulumu (initializationFailTimeout = -1
                // olduğu için) yalnız yapılandırma/IO nedeniyle başarısız olabilir;
                // bu da yeniden denenmez sayıldığından döngü hemen biter ve
                // ikinci denemedeki NoClassDefFoundError yoluna hiç girilmez.
                last = t;
                if (!isRetryable(t)) {
                    LOG.error("Database configuration error, not retrying ({}).", describe(t));
                    return new Result(Outcome.NOT_RETRYABLE, attempts, clock.nowMillis() - start, MESSAGE_CONFIG);
                }
                if (attempts == 1) {
                    LOG.warn("Database not reachable, retrying every {} ms for up to {} ms ({}).",
                            intervalMillis, budgetMillis, describe(t));
                } else {
                    LOG.debug("Database still not reachable (attempt {}, {}).", attempts, describe(t));
                }
            }

            // Sıradaki deneme, bu denemenin başlangıcından tam bir aralık sonra
            // başlar. Süre bütçesi içinde başlayamıyorsa yeni yoklama açılmaz.
            long nextAttemptAt = attemptStart + intervalMillis;
            long now = clock.nowMillis();
            if (nextAttemptAt >= deadline) {
                // Son denemeden sonra bütçenin kalanı harcanır: deadline'da yeni
                // yoklama başlatılmaz ama süre dolmadan da vazgeçilmez.
                long remaining = deadline - now;
                if (remaining > 0) {
                    try {
                        sleeper.sleep(remaining);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LOG.error("Database wait interrupted after {} attempt(s).", attempts);
                        return new Result(Outcome.TIMED_OUT, attempts, clock.nowMillis() - start, MESSAGE_TIMEOUT);
                    }
                    now = clock.nowMillis();
                }
                LOG.error("Database unavailable after {} attempt(s) in {} ms ({}).",
                        attempts, now - start, describe(last));
                return new Result(Outcome.TIMED_OUT, attempts, now - start, MESSAGE_TIMEOUT);
            }
            // Yoklama aralıktan uzun sürdüyse beklemeden hemen tekrar denenir.
            long sleepFor = nextAttemptAt - now;
            if (sleepFor <= 0) {
                continue;
            }
            try {
                sleeper.sleep(sleepFor);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.error("Database wait interrupted after {} attempt(s).", attempts);
                return new Result(Outcome.TIMED_OUT, attempts, clock.nowMillis() - start, MESSAGE_TIMEOUT);
            }
        }
    }

    /**
     * Yeniden denemeye değer mi? Geçici bağlantı hataları evet; yapılandırma,
     * kimlik doğrulama ve "veritabanı yok" gibi hatalar hayır.
     */
    static boolean isRetryable(Throwable t) {
        SQLException sql = findSqlException(t);
        if (sql == null) {
            // Yapılandırma/programlama hatası (örn. MissingConfigException) — beklemek düzeltmez.
            return false;
        }
        String state = sql.getSQLState();
        if (state != null && state.length() >= 2) {
            String cls = state.substring(0, 2).toUpperCase(Locale.ROOT);
            switch (cls) {
                case "08":          // connection exception
                    return true;
                case "28":          // invalid authorization
                case "42":          // syntax error / access rule violation
                case "3D":          // invalid catalog (bilinmeyen veritabanı)
                    return false;
                default:
                    break;
            }
        }
        // SQLState yoksa/tanınmıyorsa: yalnız açıkça geçici olan tipleri yeniden dene.
        return sql instanceof SQLTransientException
                || sql instanceof SQLRecoverableException
                || sql instanceof java.sql.SQLNonTransientConnectionException;
    }

    private static SQLException findSqlException(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException e) {
                return e;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }

    /** Log için güvenli özet: sınıf adı + SQLState + vendorCode. Mesaj/stack trace YOK. */
    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        SQLException sql = findSqlException(t);
        if (sql == null) {
            return t.getClass().getSimpleName();
        }
        return sql.getClass().getSimpleName()
                + ", SQLState=" + (sql.getSQLState() == null ? "n/a" : sql.getSQLState())
                + ", vendorCode=" + sql.getErrorCode();
    }

    /** Duvar saati yerine monotonik kaynak — saat değişiminden etkilenmez. */
    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }
}
