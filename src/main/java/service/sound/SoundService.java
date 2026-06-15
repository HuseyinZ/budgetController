package service.sound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Restoran POS için sesli bildirim servisi.
 *
 * <p>WAV dosyasına ihtiyaç duymadan, sistem ses kartından doğrudan ton
 * üretir. Kasada bağlı USB/3.5mm hoparlörden duyulur. Sistem ses çıkışı
 * (varsayılan playback device) kullanılır — Windows kontrol panelinden
 * istediğiniz hoparlöre yönlendirebilirsiniz.
 *
 * <p>Sesler ayrı thread'de oynatılır — UI dondurmaz.
 *
 * <p>Devre dışı bırakmak için: {@code -Dbudget.sound=false}
 */
public final class SoundService {

    private static final Logger LOG = LoggerFactory.getLogger(SoundService.class);
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sound-player");
        t.setDaemon(true);
        return t;
    });
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("budget.sound"));
    /** Genel ses seviyesi 0.0 - 1.0 (yüksek olsun → restoranda duyulsun) */
    private static final double VOLUME = 0.85;

    private SoundService() {}

    /** Olay türleri. */
    public enum Event {
        /** Yeni sipariş eklendi (sahibin bilmesi için). */
        NEW_ORDER,
        /** Sipariş mutfağa gönderildi. */
        KITCHEN_SENT,
        /** Sipariş hazır (mutfaktan servis için). */
        ORDER_READY,
        /** Satış başarıyla tamamlandı. */
        SALE_COMPLETE,
        /** Hata / iade / red. */
        ERROR
    }

    /** İlgili olayı asenkron çalar. */
    public static void play(Event event) {
        if (!ENABLED || event == null) return;
        EXEC.submit(() -> {
            try {
                switch (event) {
                    case NEW_ORDER     -> tones(new int[]{880, 1175}, 100);
                    case KITCHEN_SENT  -> tones(new int[]{660, 880, 1100}, 90);
                    case ORDER_READY   -> tones(new int[]{1175, 1175, 1175}, 130);
                    case SALE_COMPLETE -> tones(new int[]{523, 659, 784, 1047}, 110);
                    case ERROR         -> tones(new int[]{440, 330}, 180);
                }
            } catch (RuntimeException ex) {
                LOG.debug("Ses çalınamadı: {}", ex.getMessage());
            }
        });
    }

    /** Bir dizi tonu sırayla çalar (her biri verilen milisaniye süresinde). */
    private static void tones(int[] frequencies, int durationMs) {
        for (int freq : frequencies) {
            playTone(freq, durationMs);
            sleep(20);
        }
    }

    /** Belirtilen frekans ve süredeki saf sinüs tonunu çalar. */
    private static void playTone(int freqHz, int durationMs) {
        float sampleRate = 44100f;
        int sampleCount = (int) (sampleRate * durationMs / 1000);
        byte[] buf = new byte[sampleCount * 2]; // 16-bit mono

        for (int i = 0; i < sampleCount; i++) {
            // Sinüs dalgası — başlangıç/bitiş fade-out (click sesini önler)
            double t = i / sampleRate;
            double fade = 1.0;
            int fadeMs = Math.min(15, durationMs / 4);
            int fadeSamples = (int) (sampleRate * fadeMs / 1000);
            if (i < fadeSamples) {
                fade = (double) i / fadeSamples;
            } else if (i > sampleCount - fadeSamples) {
                fade = (double) (sampleCount - i) / fadeSamples;
            }
            short sample = (short) (Math.sin(2 * Math.PI * freqHz * t) * 32767 * VOLUME * fade);
            buf[i * 2] = (byte) (sample & 0xFF);
            buf[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine line = null;
        try {
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            line.write(buf, 0, buf.length);
            line.drain();
        } catch (LineUnavailableException ex) {
            // Ses kartı yok / kullanılıyor — sessiz geç
        } finally {
            if (line != null) {
                try {
                    line.stop();
                    line.close();
                } catch (RuntimeException ignore) {}
            }
        }
    }

    private static void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
