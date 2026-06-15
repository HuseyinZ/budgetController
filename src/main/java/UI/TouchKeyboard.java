package UI;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.KeyboardFocusManager;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;

/**
 * Windows'ta dokunmatik klavyeyi (TabTip / OSK) otomatik açan/kapatan
 * global yardımcı.
 *
 * <p><b>Çalışma şekli:</b> Bir global focus dinleyicisi takılır.
 * Focus bir {@link JTextComponent}'e geldiğinde TabTip.exe ya da
 * osk.exe başlatılır. Focus uzaklaştığında klavye gizlenir.
 *
 * <p><b>Sadece Windows.</b> Diğer OS'larda no-op.
 *
 * <p>Açma/kapama: {@code -Dbudget.keyboard=false} veya
 * {@code BUDGET_KEYBOARD=false} ortam değişkeni ile devre dışı.
 * Varsayılan: kullanıcı dokunmatik modda iken açık.
 */
public final class TouchKeyboard {

    private static volatile boolean installed = false;
    private static volatile boolean enabled = true;

    private static final String TABTIP_PATH =
            "C:\\Program Files\\Common Files\\microsoft shared\\ink\\TabTip.exe";
    private static final String OSK_PATH = "osk.exe";

    private TouchKeyboard() {}

    /**
     * Global focus dinleyicisini kurar. Tek seferlik çağırın
     * (örn. App.main içinde).
     */
    public static synchronized void install() {
        if (installed) return;
        installed = true;

        // Açıkça kapatıldı mı?
        String sys = System.getProperty("budget.keyboard");
        if (sys != null) enabled = Boolean.parseBoolean(sys.trim());
        else {
            String env = System.getenv("BUDGET_KEYBOARD");
            if (env != null) enabled = Boolean.parseBoolean(env.trim());
        }

        if (!enabled) return;
        if (!isWindows()) return;

        KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        PropertyChangeListener listener = e -> {
            Object newVal = e.getNewValue();
            if (newVal instanceof JTextComponent) {
                showKeyboard();
            } else {
                // Yeni focus text bileşeni değil — klavyeyi gizle
                hideKeyboard();
            }
        };
        kfm.addPropertyChangeListener("focusOwner", listener);
    }

    public static void showKeyboard() {
        if (!enabled || !isWindows()) return;
        try {
            File f = new File(TABTIP_PATH);
            if (f.exists()) {
                new ProcessBuilder(TABTIP_PATH).start();
            } else {
                // Eski Windows fallback
                new ProcessBuilder(OSK_PATH).start();
            }
        } catch (IOException ignored) {
            // Klavye açılamadıysa sessiz geç
        }
    }

    /**
     * TabTip'i kapatmanın resmi bir API'si yok; süreç sonlandırma
     * agresif olur. Kullanıcı close butonuyla kendisi kapatır.
     * (Burada sessiz no-op tutuyoruz.)
     */
    public static void hideKeyboard() {
        // İsteğe bağlı olarak şu komut çalıştırılabilir:
        //   taskkill /IM TabTip.exe /F
        // Ama bu hem agresif hem de izin gerektirir.
        // En iyisi: kullanıcı X'e tıklayarak kapatsın.
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }
}
