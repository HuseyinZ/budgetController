package UI.View;

import UI.AdminPanel;
import UI.AllSalesPanel;
import UI.DailyReportPanel;
import UI.ExpensesPanel;
import UI.HourlyHeatmapPanel;
import UI.ProductsPanel;
import UI.RefundHistoryPanel;
import UI.ReservationsPanel;
import UI.RestaurantTablesPanel;
import UI.WrapLayout;
import model.Role;
import model.User;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class DashboardView extends JFrame {
    private static final String CARD_FLOORS = "floors";
    private static final String CARD_USERS = "users";
    private static final String CARD_PRODUCTS = "products";
    private static final String CARD_EXPENSES = "expenses";
    private static final String CARD_SALES = "sales";
    private static final String CARD_DAILY = "daily";
    private static final String CARD_HOURLY = "hourly";
    private static final String CARD_REFUNDS = "refunds";
    private static final String CARD_RESERVATIONS = "reservations";

    private final AppState appState;
    private service.security.IdleLogoutWatcher idleWatcher;
    private final User currentUser;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentPanel = new JPanel(cardLayout);
    private final JLabel liveClockLbl = new JLabel(" ");
    private javax.swing.Timer clockTimer;
    private java.time.LocalDate lastSeenDay = java.time.LocalDate.now();

    public DashboardView(AppState appState, User user) {
        super("Budget Controller");
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = Objects.requireNonNull(user, "user");

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        // Önce content pane'i (opsiyonel arka plan görseli ile) ayarla,
        // sonra layout ve child component'ları ekle.
        applyBackgroundIfAvailable();
        setLayout(new BorderLayout());

        contentPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel toolbar = buildToolbar();
        add(toolbar, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        startClockTimer();

        // Açılışta ekranın %85'i kadar bir alanı kapla (yüksek çözünürlükte rahat)
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = Math.min(1920, Math.max(1280, (int) (screen.width * 0.85)));
        int h = Math.min(1200, Math.max(800,  (int) (screen.height * 0.85)));
        setSize(w, h);
        setLocationRelativeTo(null);
        // Yine de tam ekran açılışı tercih ederseniz:
        setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);

        // Otomatik logout — 5 dk hareketsizlik sonrası çıkış.
        // -Dbudget.idle.minutes=10 ile değiştirilebilir; 0 = devre dışı.
        long idleMinutes = parseLongProperty("budget.idle.minutes", 5L);
        if (idleMinutes > 0) {
            idleWatcher = new service.security.IdleLogoutWatcher(
                    idleMinutes * 60_000L, this::performIdleLogout);
            idleWatcher.start();
        }
    }

    private static long parseLongProperty(String key, long defaultValue) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException ex) { return defaultValue; }
    }

    /** Idle eşik aşıldığında çağrılır — dashboard'u kapat ve login'e dön. */
    private void performIdleLogout() {
        if (idleWatcher != null) idleWatcher.stop();
        // Uyarı + çıkış
        try {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Hareketsizlik nedeniyle oturumunuz kapatıldı.",
                    "Otomatik Çıkış", javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException ignored) {}
        // Login ekranını aç
        try {
            UI.View.LoginView login = new UI.View.LoginView();
            login.setLoginListener(u -> {
                DashboardView next = new DashboardView(appState, u);
                next.open();
            });
            login.open();
        } catch (RuntimeException ex) {
            // login açılamadıysa programı tamamen kapat
        }
        dispose();
    }

    @Override
    public void dispose() {
        if (idleWatcher != null) {
            idleWatcher.stop();
            idleWatcher = null;
        }
        if (clockTimer != null) {
            clockTimer.stop();
            clockTimer = null;
        }
        super.dispose();
    }

    private JPanel buildToolbar() {
        // WrapLayout — dar pencerede butonlar alt satıra geçer (FlowLayout kesip atıyor)
        JPanel toolbar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 8));
        toolbar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        ButtonGroup group = new ButtonGroup();
        Role role = currentUser.getRole();

        List<CardConfig> configs = new ArrayList<>();
        // Görünürlük politikası:
        //   - "Katlar":          tüm roller (garson sadece izinli alanları görür)
        //   - "Ürünler":         ADMIN + KASIYER (fiyat/porsiyon ayarlama)
        //   - "Kullanıcı İşlemleri": ADMIN + KASIYER (kasiyer garson alan yetkilerini düzenler)
        //   - "Giderler":        ADMIN + KASIYER
        //   - "Satışlar":        SADECE ADMIN (gelir bilgisi)
        //   - "Net Kar":         SADECE ADMIN
        configs.add(new CardConfig(CARD_FLOORS, "Katlar",
                () -> new JScrollPane(new RestaurantTablesPanel(appState, currentUser)),
                r -> true));
        configs.add(new CardConfig(CARD_PRODUCTS, "Ürünler",
                () -> new ProductsPanel(appState, currentUser),
                r -> r == Role.ADMIN || r == Role.KASIYER));
        configs.add(new CardConfig(CARD_USERS, "Kullanıcı İşlemleri",
                () -> new AdminPanel(currentUser),
                r -> r == Role.ADMIN || r == Role.KASIYER));
        configs.add(new CardConfig(CARD_EXPENSES, "Giderler",
                () -> new ExpensesPanel(appState, currentUser),
                r -> r == Role.ADMIN || r == Role.KASIYER));
        configs.add(new CardConfig(CARD_SALES, "Satışlar",
                () -> new AllSalesPanel(appState),
                r -> r == Role.ADMIN));
        configs.add(new CardConfig(CARD_DAILY, "Gün Sonu",
                () -> new DailyReportPanel(appState),
                r -> r == Role.ADMIN));
        configs.add(new CardConfig(CARD_HOURLY, "Saatlik Yoğunluk",
                () -> new HourlyHeatmapPanel(appState),
                r -> r == Role.ADMIN));
        configs.add(new CardConfig(CARD_REFUNDS, "İşlem Geçmişi",
                () -> new RefundHistoryPanel(appState),
                r -> r == Role.ADMIN));
        configs.add(new CardConfig(CARD_RESERVATIONS, "Rezervasyonlar",
                () -> new ReservationsPanel(currentUser),
                r -> r == Role.ADMIN || r == Role.KASIYER || r == Role.GARSON));

        String initialCard = null;

        for (CardConfig config : configs) {
            if (!config.visible().test(role)) {
                continue;
            }

            JComponent component = config.component().get();
            contentPanel.add(component, config.card());

            JToggleButton button = new JToggleButton(config.label());
            // Genişliği etikete göre dinamik: en az 200 px, "Kullanıcı İşlemleri" gibi
            // uzun etiketler için 240 px ve daha fazlasına çıkar.
            int textWidth = button.getFontMetrics(button.getFont()).stringWidth(config.label());
            int width = Math.max(200, textWidth + 60);
            button.setPreferredSize(new Dimension(width, 48));

            button.addActionListener(e -> cardLayout.show(contentPanel, config.card()));
            toolbar.add(button);
            group.add(button);

            if (initialCard == null) {
                initialCard = config.card();
                button.setSelected(true);
                cardLayout.show(contentPanel, initialCard);
            }
        }

        if (initialCard == null) {
            JLabel empty = new JLabel("Bu kullanıcı için yetkili modül bulunamadı");
            empty.setHorizontalAlignment(SwingConstants.CENTER);
            contentPanel.add(empty, "empty");
            cardLayout.show(contentPanel, "empty");
        }

        return toolbar;
    }

    /**
     * Classpath'te <code>/images/background.jpg</code> varsa Dashboard'un
     * arka planına yansıtır. Yoksa düz renkle açılır.
     */
    private void applyBackgroundIfAvailable() {
        java.net.URL bg = DashboardView.class.getResource("/images/background.jpg");
        if (bg == null) {
            bg = DashboardView.class.getResource("/images/background.png");
        }
        if (bg == null) return;
        try {
            java.awt.Image image = javax.imageio.ImageIO.read(bg);
            if (image == null) return;
            // Content pane'i özel bir JPanel ile değiştir
            final java.awt.Image bgImg = image;
            javax.swing.JPanel backed = new javax.swing.JPanel(new BorderLayout()) {
                @Override
                protected void paintComponent(java.awt.Graphics g) {
                    super.paintComponent(g);
                    g.drawImage(bgImg, 0, 0, getWidth(), getHeight(), this);
                }
            };
            backed.setOpaque(true);
            setContentPane(backed);
            // contentPanel ve toolbar tekrar eklenmeli; constructor sonrasında ekleniyor
        } catch (java.io.IOException ignored) {
            // sessiz geç
        }
    }

    public void open() {
        if (SwingUtilities.isEventDispatchThread()) {
            setVisible(true);
        } else {
            SwingUtilities.invokeLater(() -> setVisible(true));
        }
    }

    private record CardConfig(String card, String label, Supplier<JComponent> component, Predicate<Role> visible) {
    }

    // ============================================================
    //   Canlı saat + gün geçişi otomatik tazeleme
    // ============================================================
    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 4));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(220, 220, 220)));
        liveClockLbl.setFont(liveClockLbl.getFont().deriveFont(Font.PLAIN, 12f));
        liveClockLbl.setForeground(new Color(80, 80, 80));
        bar.add(liveClockLbl);
        tickClock();
        return bar;
    }

    private void startClockTimer() {
        if (clockTimer != null) clockTimer.stop();
        // 30 sn'de bir tetikle — UI thread'de
        clockTimer = new javax.swing.Timer(30_000, e -> tickClock());
        clockTimer.setInitialDelay(30_000);
        clockTimer.start();
    }

    private void tickClock() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        liveClockLbl.setText(String.format("🕒 %02d.%02d.%d  %02d:%02d",
                now.getDayOfMonth(), now.getMonthValue(), now.getYear(),
                now.getHour(), now.getMinute()));
        java.time.LocalDate today = now.toLocalDate();
        if (!today.equals(lastSeenDay)) {
            lastSeenDay = today;
            // Görünür panelleri "bugüne" çek
            refreshTimeSensitivePanels();
        }
    }

    /**
     * contentPanel'deki şu an görünür olan card içeriğini bulur ve
     * DailyReportPanel / HourlyHeatmapPanel ise {@code resetToToday()} çağırır.
     */
    private void refreshTimeSensitivePanels() {
        for (Component c : contentPanel.getComponents()) {
            if (!c.isVisible()) continue;
            JComponent inner = unwrap(c);
            if (inner instanceof UI.DailyReportPanel p)   p.resetToToday();
            if (inner instanceof UI.HourlyHeatmapPanel p) p.resetToToday();
        }
    }

    /** JScrollPane içine sarılmışsa içeriği çıkar. */
    private JComponent unwrap(Component c) {
        if (c instanceof JScrollPane sp) {
            Component v = sp.getViewport().getView();
            return (v instanceof JComponent jc) ? jc : null;
        }
        return (c instanceof JComponent jc) ? jc : null;
    }
}
