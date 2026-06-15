package service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Kullanıcı belirli süre boyunca etkileşim yapmazsa otomatik logout tetikleyici.
 *
 * <p>AWT olaylarını dinleyerek son etkinlik zamanını günceller.
 * Zamanlayıcı belirli aralıklarla bunu kontrol eder; eşik aşıldıysa
 * verilen logout işlemini Swing thread'inde çalıştırır.
 *
 * <p>Kullanım:
 * <pre>
 * IdleLogoutWatcher watcher = new IdleLogoutWatcher(5 * 60_000L, () -> doLogout());
 * watcher.start();
 * // logout sonrası: watcher.stop();
 * </pre>
 */
public final class IdleLogoutWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(IdleLogoutWatcher.class);

    /** Hangi AWT olaylarını "aktivite" sayıyoruz. */
    private static final long EVENT_MASK =
            AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
                    | AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK;

    private final long idleThresholdMillis;
    private final Runnable onIdleLogout;
    private volatile long lastActivity = System.currentTimeMillis();
    private ScheduledExecutorService scheduler;
    private AWTEventListener listener;
    private volatile boolean fired = false;

    public IdleLogoutWatcher(long idleThresholdMillis, Runnable onIdleLogout) {
        if (idleThresholdMillis < 10_000L) idleThresholdMillis = 10_000L;
        this.idleThresholdMillis = idleThresholdMillis;
        this.onIdleLogout = onIdleLogout;
    }

    public synchronized void start() {
        if (scheduler != null) return; // zaten başladı
        lastActivity = System.currentTimeMillis();
        fired = false;
        listener = event -> lastActivity = System.currentTimeMillis();
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, EVENT_MASK);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idle-logout-watcher");
            t.setDaemon(true);
            return t;
        });
        // Her 15 saniyede bir kontrol et
        scheduler.scheduleAtFixedRate(this::check, 15, 15, TimeUnit.SECONDS);
        LOG.info("Otomatik logout aktif: {} dakika hareketsizlik sonrası", idleThresholdMillis / 60_000L);
    }

    public synchronized void stop() {
        if (listener != null) {
            try { Toolkit.getDefaultToolkit().removeAWTEventListener(listener); }
            catch (RuntimeException ignored) {}
            listener = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void check() {
        if (fired) return;
        long idle = System.currentTimeMillis() - lastActivity;
        if (idle >= idleThresholdMillis) {
            fired = true;
            SwingUtilities.invokeLater(() -> {
                try {
                    if (onIdleLogout != null) onIdleLogout.run();
                } catch (RuntimeException ex) {
                    LOG.warn("Otomatik logout sırasında hata: {}", ex.getMessage());
                }
            });
        }
    }
}
