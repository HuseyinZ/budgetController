package org.budget;

import UI.TouchKeyboard;
import UI.View.LoginView;
import UI.View.DashboardView;
import com.formdev.flatlaf.FlatLightLaf;
import model.User;
import org.jetbrains.annotations.NotNull;
import service.BackupService;
import service.UserService;
import service.api.ApiServer;
import service.api.SecurityConfig;
import state.AppState;


import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class App {
    private static final AppState APP_STATE = AppState.getInstance();
    /** Periyodik MySQL yedek servisi — Singleton. */
    private static final BackupService BACKUP_SERVICE = new BackupService();
    /** REST API server — telefonla uzaktan erişim için. */
    private static final ApiServer API_SERVER = new ApiServer(APP_STATE);
    /** Otomatik Gün Sonu mail gönderimi — config'te kapalıysa devreye girmez. */
    private static final service.email.DailyReportScheduler DAILY_REPORT_SCHEDULER =
            new service.email.DailyReportScheduler(APP_STATE);

    /**
     * Dokunmatik ekran kullanımı için ortam değişkeni veya
     * -Dbudget.touch=true ile devreye girer.
     * Açık olduğunda font/satır yüksekliği gibi UI defaults büyütülür.
     */
    private static final boolean TOUCH_MODE = resolveTouchMode();

    private static boolean resolveTouchMode() {
        String sys = System.getProperty("budget.touch");
        if (sys != null) return Boolean.parseBoolean(sys.trim());
        String env = System.getenv("BUDGET_TOUCH");
        if (env != null) return Boolean.parseBoolean(env.trim());
        return true;   // varsayılan: dokunmatik dostu
    }

    public static void main(String[] args) {

        // İlk olarak DB şema patch'lerini uygula — eski şemada sorunlu CHECK
        // constraint'leri varsa kaldır (sipariş eklemede patlamaması için)
        service.db.SchemaPatcher.applyAll();

        // Otomatik yedek — ilk 5 dakika sonra başla, sonra her 60 dakikada bir
        BACKUP_SERVICE.startScheduler(5, 60);

        // Otomatik Gün Sonu e-posta gönderimi (email-config.properties'te aktifse)
        DAILY_REPORT_SCHEDULER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(DAILY_REPORT_SCHEDULER::stop,
                "DailyReportScheduler-shutdown"));

        // REST API server — mobil/uzaktan erişim için
        // GÜVENLİK:
        //   - Üretimde API_HTTP_ENABLED=false önerilir; HTTPS önünde reverse proxy
        //     (Tailscale Serve / Nginx) tercih edilir.
        //   - API_BIND_ADDRESS=127.0.0.1 ile sadece local'e bind edilebilir.
        if (Boolean.parseBoolean(System.getProperty("api.enabled", "true"))) {
            Thread.interrupted();
            Thread apiThread = new Thread(() -> {
                try {
                    if (service.api.SecurityConfig.isProduction()
                            && service.api.SecurityConfig.httpEnabled()
                            && !service.api.SecurityConfig.httpsEnabled()) {
                        System.err.println("UYARI: Üretim modunda HTTP açık ve HTTPS kapalı. "
                                + "Reverse proxy (Tailscale/Nginx) arkasında değilseniz API_BIND_ADDRESS=127.0.0.1 yapın.");
                    }
                    if (service.api.SecurityConfig.httpEnabled()) {
                        int apiPort = service.api.SecurityConfig.httpPort();
                        API_SERVER.start(apiPort);
                    } else {
                        System.out.println("REST API HTTP devre dışı (API_HTTP_ENABLED=false).");
                    }
                } catch (RuntimeException ex) {
                    System.err.println("REST API başlatılamadı: " + ex.getMessage());
                    // Stack trace'i log'a düşürelim ama stderr'e fazla detay vermeyelim
                    if (ex.getCause() != null) {
                        org.slf4j.LoggerFactory.getLogger(App.class)
                                .error("API hatası", ex.getCause());
                    }
                }
            }, "api-startup");
            apiThread.setDaemon(true);
            apiThread.start();
        }

        SwingUtilities.invokeLater(() -> {
            // FlatLaf global font ölçeği — büyük metin için
            if (TOUCH_MODE) {
                System.setProperty("flatlaf.uiScale", "1.25");
            }
            FlatLightLaf.setup();
            applyTouchDefaults();

            // Dokunmatik klavye (Windows TabTip) — text alanlara focus gelince açar
            TouchKeyboard.install();

            LoginView loginView = new LoginView();
            loginView.setLoginListener(App::onLogin);
            loginView.open();
        });
    }

    /** Manuel yedek tetikleme için (gelecekte admin UI'sından çağrılabilir). */
    public static boolean triggerManualBackup() {
        return BACKUP_SERVICE.backupNow();
    }

    /**
     * Swing'in UIManager'ında genel ayarları büyütür — dokunmatik için.
     * Bu sayede her panel/dialog kendi içinde özel ayar yapmasa bile
     * butonlar, tablo satırları, font'lar daha geniş ve okunabilir olur.
     */
    private static void applyTouchDefaults() {
        if (!TOUCH_MODE) return;
        Font baseFont = UIManager.getFont("defaultFont");
        if (baseFont == null) baseFont = new Font("Dialog", Font.PLAIN, 13);
        Font larger = baseFont.deriveFont(Font.PLAIN, 16f);
        Font bold   = baseFont.deriveFont(Font.BOLD, 16f);

        // Genel font
        UIManager.put("defaultFont", larger);
        UIManager.put("Button.font", bold);
        UIManager.put("Label.font", larger);
        UIManager.put("TextField.font", larger);
        UIManager.put("PasswordField.font", larger);
        UIManager.put("ComboBox.font", larger);
        UIManager.put("Table.font", larger);
        UIManager.put("TableHeader.font", bold);
        UIManager.put("Spinner.font", larger);
        UIManager.put("TabbedPane.font", larger);
        UIManager.put("ToggleButton.font", bold);
        UIManager.put("CheckBox.font", larger);
        UIManager.put("RadioButton.font", larger);

        // Yüksek satırlar (tablo + liste)
        UIManager.put("Table.rowHeight", 36);
        UIManager.put("List.rowHeight", 32);

        // Button minimum height — FlatLaf-spesifik anahtarlar
        UIManager.put("Button.minimumHeight", 44);
        UIManager.put("ToggleButton.minimumHeight", 44);
        UIManager.put("TextField.minimumHeight", 36);
        UIManager.put("PasswordField.minimumHeight", 36);
        UIManager.put("ComboBox.minimumHeight", 40);
        UIManager.put("Spinner.minimumHeight", 36);

        // Daha rahat iç boşluklar
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.focusWidth", 2);
    }

    private static void onLogin(@NotNull User user) {
        User authenticated = Objects.requireNonNull(user, "user");
        DashboardView dashboard = new DashboardView(APP_STATE, authenticated);
        dashboard.open();
    }
}
