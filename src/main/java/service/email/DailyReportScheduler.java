package service.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.report.ReportWorkbookBuilder;
import state.AppState;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Konfigüre edilen saatte her gün Gün Sonu raporunu e-posta ile gönderir.
 *
 * <p>{@code email-config.properties} dosyasından okunan ayarlar:
 * <ul>
 *   <li>{@code report.auto.enabled} — true/false</li>
 *   <li>{@code report.auto.time}    — HH:mm formatında (örn. "23:55")</li>
 *   <li>{@code report.to}           — alıcı(lar), virgülle ayrılır</li>
 * </ul>
 *
 * <p>Strateji: {@link #start()} çağrıldığında, bir sonraki tetik zamanı hesaplanır
 * ve {@link ScheduledExecutorService} ile zamanlanır. Tetiklenince:
 * <ol>
 *   <li>Bugünün (LocalDate.now) Excel raporu üretilir,</li>
 *   <li>{@link EmailService} ile gönderilir,</li>
 *   <li>Bir sonraki gün için yeniden zamanlanır.</li>
 * </ol>
 *
 * <p>Bilgisayar tetik saatinde kapalıysa o günkü gönderim atlanır — yine de
 * uygulama tekrar açıldığında bir sonraki gün için zamanlama devam eder.
 */
public class DailyReportScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(DailyReportScheduler.class);
    private static final String CONFIG_PATH = "/email-config.properties";

    private final AppState appState;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> pending;
    private volatile LocalDate lastSentDay; // aynı gün iki kez göndermemek için

    public DailyReportScheduler(AppState appState) {
        this.appState = appState;
    }

    /**
     * Eğer config'te {@code report.auto.enabled=true} ise zamanlayıcıyı başlatır.
     * Aksi halde sessiz geçer.
     */
    public synchronized void start() {
        Properties cfg = loadConfig();
        if (!"true".equalsIgnoreCase(cfg.getProperty("report.auto.enabled", "false"))) {
            LOG.info("Otomatik Gün Sonu gönderimi KAPALI (report.auto.enabled=false)");
            return;
        }
        LocalTime trigger;
        try {
            trigger = LocalTime.parse(cfg.getProperty("report.auto.time", "23:55").trim());
        } catch (Exception ex) {
            LOG.warn("Geçersiz report.auto.time, varsayılan 23:55 kullanılıyor: {}", ex.getMessage());
            trigger = LocalTime.of(23, 55);
        }
        String to = cfg.getProperty("report.to", "").trim();
        if (to.isEmpty()) {
            LOG.warn("Otomatik gönderim aktif ama 'report.to' boş — devreye alınmadı.");
            return;
        }
        EmailService emailService = new EmailService();
        if (!emailService.isConfigured()) {
            LOG.warn("Otomatik gönderim aktif ama SMTP yapılandırması eksik — devreye alınmadı.");
            return;
        }

        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "DailyReportScheduler");
                t.setDaemon(true);
                return t;
            });
        }
        scheduleNext(trigger, to, emailService);
    }

    private synchronized void scheduleNext(LocalTime triggerAt, String to, EmailService emailService) {
        Duration until = durationUntilNext(triggerAt);
        LOG.info("Otomatik Gün Sonu gönderimi zamanlandı: hedef saat {} (yaklaşık {} dk sonra)",
                triggerAt, until.toMinutes());
        pending = executor.schedule(() -> runAndReschedule(triggerAt, to, emailService),
                until.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Tetiklendiğinde: bugünün raporunu üret, gönder, bir sonraki güne yeniden zamanla. */
    private void runAndReschedule(LocalTime triggerAt, String to, EmailService emailService) {
        try {
            LocalDate today = LocalDate.now();
            if (today.equals(lastSentDay)) {
                LOG.info("Otomatik gönderim atlandı: bugün ({}) zaten gönderilmiş.", today);
            } else {
                LOG.info("Otomatik Gün Sonu raporu hazırlanıyor: {}", today);
                byte[] bytes = ReportWorkbookBuilder.buildDailyReport(appState, today);
                String fileName = ReportWorkbookBuilder.dailyFileName(today);
                String subject = "[Dağkapı Ciğercisi] Gün Sonu Raporu — " + today;
                String body = "Tarih: " + today + "\n"
                        + "Otomatik Gün Sonu raporu ekteki Excel dosyasındadır.\n\n"
                        + "— Dağkapı Ciğercisi POS";
                emailService.sendWithAttachment(to, subject, body, fileName, bytes,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                lastSentDay = today;
                // GÜVENLİK: alıcı adresi log'da maskelenir.
                LOG.info("Otomatik Gün Sonu raporu gönderildi: {} → {}",
                        today, service.util.Mask.email(to));
            }
        } catch (Exception ex) {
            LOG.warn("Otomatik gönderim başarısız: {}", ex.getMessage(), ex);
        } finally {
            // Bir sonraki güne zamanla
            scheduleNext(triggerAt, to, emailService);
        }
    }

    /** Sonraki "triggerAt" saatine kadar olan süre. Bugün geçmişse yarına atar. */
    static Duration durationUntilNext(LocalTime triggerAt) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime target = LocalDate.now().atTime(triggerAt);
        if (!target.isAfter(now)) {
            target = target.plusDays(1);
        }
        Duration d = Duration.between(now, target);
        // Negatif olmasın
        if (d.isNegative() || d.isZero()) d = Duration.ofMinutes(1);
        return d;
    }

    public synchronized void stop() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
        LOG.info("DailyReportScheduler durduruldu");
    }

    private Properties loadConfig() {
        Properties p = new Properties();
        try (InputStream in = DailyReportScheduler.class.getResourceAsStream(CONFIG_PATH)) {
            if (in == null) return p;
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            LOG.warn("email-config.properties okunamadı: {}", ex.getMessage());
        }
        return p;
    }
}
