package UI;

import DataConnection.Db;
import model.MoneyUtil;
import model.Payment;
import model.PaymentMethod;
import service.PaymentService;
import state.AppState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * Gün Sonu Raporu paneli.
 *
 * <p>Yalnız ADMIN erişebilir (DashboardView'da filtrelenir).
 * Seçilen tarih için:
 * <ul>
 *   <li>Toplam ciro</li>
 *   <li>Ödeme yöntemine göre dağılım (Nakit / Kart / Havale)</li>
 *   <li>Sipariş sayısı</li>
 *   <li>Toplam gider</li>
 *   <li>Net kar</li>
 *   <li>Saatlik satış adetleri (özet)</li>
 * </ul>
 * gösterir; "Excel'e aktar" ile XLSX olarak dışa aktarır.
 */
public class DailyReportPanel extends JPanel {

    private static final Logger LOG = LoggerFactory.getLogger(DailyReportPanel.class);

    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("HH:00");

    private final AppState appState;
    private final PaymentService paymentService;
    private final JSpinner dateSpinner = new JSpinner(
            new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JSpinner monthSpinner = new JSpinner(
            new SpinnerDateModel(new Date(), null, null, java.util.Calendar.MONTH));

    /** "DAY" veya "MONTH". */
    private String mode = "DAY";
    private final JLabel dateLabel = new JLabel("Tarih: ");
    private final JLabel monthLabel = new JLabel("Ay: ");
    private final JToggleButton dayBtn = new JToggleButton("Günlük", true);
    private final JToggleButton monthBtn = new JToggleButton("Aylık", false);

    // Üst panel — özet kartları
    private final JLabel totalSalesLbl   = newBigValue("0,00 ₺");
    private final JLabel totalExpenseLbl = newBigValue("0,00 ₺");
    private final JLabel netProfitLbl    = newBigValue("0,00 ₺");
    private final JLabel orderCountLbl   = newBigValue("0");

    // Detay panelleri
    private final DefaultTableModel paymentBreakdownModel =
            new DefaultTableModel(new Object[]{"Ödeme Yöntemi", "Adet", "Tutar"}, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
    private final JTable paymentBreakdownTable = new JTable(paymentBreakdownModel);

    private final DefaultTableModel hourlyModel =
            new DefaultTableModel(new Object[]{"Saat", "İşlem Sayısı", "Tutar"}, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
    private final JTable hourlyTable = new JTable(hourlyModel);

    private final DefaultTableModel productSummaryModel =
            new DefaultTableModel(new Object[]{"Ürün", "Birim", "Satılan", "Porsiyon Karşılığı"}, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
    private final JTable productSummaryTable = new JTable(productSummaryModel);

    public DailyReportPanel(AppState appState) {
        this.appState = appState;
        this.paymentService = new PaymentService();

        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);

        refresh();
    }

    private JComponent buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        // --- Görünüm modu (Günlük / Aylık) ---
        ButtonGroup grp = new ButtonGroup();
        grp.add(dayBtn);
        grp.add(monthBtn);
        styleToggle(dayBtn);
        styleToggle(monthBtn);
        dayBtn.addActionListener(e -> setMode("DAY"));
        monthBtn.addActionListener(e -> setMode("MONTH"));
        bar.add(dayBtn);
        bar.add(monthBtn);
        bar.addSeparator();

        // --- Tarih / Ay seçici ---
        bar.add(dateLabel);
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "dd-MM-yyyy"));
        dateSpinner.setPreferredSize(new Dimension(160, 36));
        bar.add(dateSpinner);

        bar.add(monthLabel);
        monthSpinner.setEditor(new JSpinner.DateEditor(monthSpinner, "MM-yyyy"));
        monthSpinner.setPreferredSize(new Dimension(140, 36));
        bar.add(monthSpinner);
        monthLabel.setVisible(false);
        monthSpinner.setVisible(false);

        bar.addSeparator();
        JButton refresh = new JButton("Hesapla");
        refresh.setPreferredSize(new Dimension(140, 40));
        refresh.addActionListener(e -> refresh());
        bar.add(refresh);

        bar.addSeparator();
        JButton exportBtn = new JButton("Excel'e Aktar");
        exportBtn.setPreferredSize(new Dimension(160, 40));
        exportBtn.addActionListener(e -> exportExcel());
        bar.add(exportBtn);

        JButton emailBtn = new JButton("📧 E-posta Gönder");
        emailBtn.setPreferredSize(new Dimension(180, 40));
        emailBtn.addActionListener(e -> emailExcel());
        bar.add(emailBtn);

        bar.addSeparator();
        JButton today = new JButton("Bugün");
        today.addActionListener(e -> {
            dateSpinner.setValue(new Date());
            monthSpinner.setValue(new Date());
            refresh();
        });
        bar.add(today);

        return bar;
    }

    private void styleToggle(JToggleButton b) {
        b.setPreferredSize(new Dimension(90, 36));
        b.setFocusPainted(false);
    }

    private void setMode(String newMode) {
        if (newMode.equals(this.mode)) return;
        this.mode = newMode;
        boolean day = "DAY".equals(newMode);
        dateLabel.setVisible(day);
        dateSpinner.setVisible(day);
        monthLabel.setVisible(!day);
        monthSpinner.setVisible(!day);
        refresh();
    }

    private JComponent buildCenter() {
        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(buildSummaryCards(), BorderLayout.NORTH);

        JPanel topGrid = new JPanel(new GridLayout(1, 2, 8, 8));
        topGrid.add(wrap("Ödeme Yöntemi Dağılımı", new JScrollPane(paymentBreakdownTable)));
        topGrid.add(wrap("Saatlik İşlem Dağılımı",  new JScrollPane(hourlyTable)));

        JComponent bottom = wrap("Ürün Satış Özeti (porsiyon karşılıkları)",
                new JScrollPane(productSummaryTable));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topGrid, bottom);
        split.setResizeWeight(0.45);
        split.setBorder(null);
        center.add(split, BorderLayout.CENTER);
        return center;
    }

    // ---- Ürün satış özeti (tek tablo) ----
    private static class ProductSummaryRow {
        final String productName;
        final String unitLabel;
        final int totalQty;            // toplam satılan adet (şiş veya porsiyon)
        final int piecesPerPortion;    // 0 → porsiyon bazlı; >0 → şiş bazlı
        ProductSummaryRow(String name, String unit, int qty, int pp) {
            this.productName = name;
            this.unitLabel = unit;
            this.totalQty = qty;
            this.piecesPerPortion = pp;
        }
        double portionEquivalent() {
            if (piecesPerPortion <= 0) return totalQty;
            return totalQty / (double) piecesPerPortion;
        }
    }

    private List<ProductSummaryRow> loadProductSummary(LocalDate date) {
        // O gün ÖDEMESİ alınan siparişlerin kalemlerini topla.
        // Payment tablosu üzerinden gidiyoruz çünkü order.status sütununun değeri
        // (string/numeric) DB'lere göre farklılık gösterebiliyor.
        // noinspection SqlResolve, SqlNoDataSourceInspection
        final String sql =
                "SELECT oi.product_name, " +
                "       COALESCE(oi.unit_label, '') AS unit_label, " +
                "       COALESCE(MAX(oi.pieces_per_portion), 0) AS pp, " +
                "       SUM(oi.quantity) AS total_qty " +
                "  FROM order_items oi " +
                "  JOIN payments    p ON p.order_id = oi.order_id " +
                " WHERE DATE(p.paid_at) = ? " +
                " GROUP BY oi.product_name, COALESCE(oi.unit_label, '') " +
                " ORDER BY total_qty DESC";
        List<ProductSummaryRow> rows = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, java.sql.Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ProductSummaryRow(
                            rs.getString("product_name"),
                            rs.getString("unit_label"),
                            rs.getInt("total_qty"),
                            rs.getInt("pp")));
                }
            }
        } catch (SQLException ex) {
            // Snapshot sütunları yoksa eski şema için basit fallback dene
            try {
                rows.addAll(loadProductSummaryFallback(date));
            } catch (SQLException fallbackEx) {
                // Özet boş kalır — yalnızca tanı için warn
                LOG.warn("Daily product summary unavailable; primary={}, fallback={}", ex.toString(), fallbackEx.toString());
            }
        }
        return rows;
    }

    /** Aylık varyant — verilen ayın tamamı için ürün satış özeti. */
    private List<ProductSummaryRow> loadProductSummary(YearMonth ym) {
        LocalDate start = ym.atDay(1);
        LocalDate end = start.plusMonths(1); // exclusive
        // noinspection SqlResolve, SqlNoDataSourceInspection
        final String sql =
                "SELECT oi.product_name, " +
                "       COALESCE(oi.unit_label, '') AS unit_label, " +
                "       COALESCE(MAX(oi.pieces_per_portion), 0) AS pp, " +
                "       SUM(oi.quantity) AS total_qty " +
                "  FROM order_items oi " +
                "  JOIN payments    p ON p.order_id = oi.order_id " +
                " WHERE p.paid_at >= ? AND p.paid_at < ? " +
                " GROUP BY oi.product_name, COALESCE(oi.unit_label, '') " +
                " ORDER BY total_qty DESC";
        List<ProductSummaryRow> rows = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, java.sql.Timestamp.valueOf(start.atStartOfDay()));
            ps.setTimestamp(2, java.sql.Timestamp.valueOf(end.atStartOfDay()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ProductSummaryRow(
                            rs.getString("product_name"),
                            rs.getString("unit_label"),
                            rs.getInt("total_qty"),
                            rs.getInt("pp")));
                }
            }
        } catch (SQLException ex) {
            try {
                rows.addAll(loadProductSummaryFallback(start, end));
            } catch (SQLException fallbackEx) {
                // Özet boş kalır — yalnızca tanı için warn
                LOG.warn("Monthly product summary unavailable; primary={}, fallback={}", ex.toString(), fallbackEx.toString());
            }
        }
        return rows;
    }

    /** Eski şema için aylık fallback. */
    private List<ProductSummaryRow> loadProductSummaryFallback(LocalDate startInclusive, LocalDate endExclusive)
            throws SQLException {
        // noinspection SqlResolve, SqlNoDataSourceInspection
        final String sql =
                "SELECT oi.product_name, SUM(oi.quantity) AS total_qty " +
                "  FROM order_items oi " +
                "  JOIN payments p ON p.order_id = oi.order_id " +
                " WHERE p.paid_at >= ? AND p.paid_at < ? " +
                " GROUP BY oi.product_name " +
                " ORDER BY total_qty DESC";
        List<ProductSummaryRow> out = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, java.sql.Timestamp.valueOf(startInclusive.atStartOfDay()));
            ps.setTimestamp(2, java.sql.Timestamp.valueOf(endExclusive.atStartOfDay()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ProductSummaryRow(
                            rs.getString("product_name"),
                            "",
                            rs.getInt("total_qty"),
                            0));
                }
            }
        }
        return out;
    }

    /** Eski şema için: pieces_per_portion / unit_label yoksa minimum sorgu. */
    private List<ProductSummaryRow> loadProductSummaryFallback(LocalDate date) throws SQLException {
        // noinspection SqlResolve, SqlNoDataSourceInspection
        final String sql =
                "SELECT oi.product_name, SUM(oi.quantity) AS total_qty " +
                "  FROM order_items oi " +
                "  JOIN payments p ON p.order_id = oi.order_id " +
                " WHERE DATE(p.paid_at) = ? " +
                " GROUP BY oi.product_name " +
                " ORDER BY total_qty DESC";
        List<ProductSummaryRow> out = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, java.sql.Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ProductSummaryRow(
                            rs.getString("product_name"),
                            "",
                            rs.getInt("total_qty"),
                            0));
                }
            }
        }
        return out;
    }

    private JComponent buildSummaryCards() {
        JPanel cards = new JPanel(new GridLayout(1, 4, 8, 8));
        cards.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        cards.add(card("Toplam Ciro",     totalSalesLbl,   new Color(220, 247, 220)));
        cards.add(card("Toplam Gider",    totalExpenseLbl, new Color(252, 232, 232)));
        cards.add(card("Net Kar",         netProfitLbl,    new Color(220, 235, 252)));
        cards.add(card("Sipariş Sayısı",  orderCountLbl,   new Color(252, 245, 220)));
        return cards;
    }

    private JComponent card(String title, JLabel valueLabel, Color bg) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(bg);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 210, 210)),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)));
        JLabel t = new JLabel(title);
        t.setForeground(Color.DARK_GRAY);
        t.setFont(t.getFont().deriveFont(java.awt.Font.PLAIN, 13f));
        p.add(t, BorderLayout.NORTH);
        valueLabel.setForeground(new Color(20, 60, 100));
        p.add(valueLabel, BorderLayout.CENTER);
        return p;
    }

    private JPanel wrap(String title, JComponent inner) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(inner, BorderLayout.CENTER);
        return p;
    }

    private static JLabel newBigValue(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD, 22f));
        return l;
    }

    // ---- Hesaplama ----

    private void refresh() {
        boolean monthly = "MONTH".equals(mode);
        List<Payment> payments;
        BigDecimal totalExpense;
        List<ProductSummaryRow> productRows;

        if (monthly) {
            YearMonth ym = pickMonth();
            payments = paymentService.getPaymentsInMonth(ym.getYear(), ym.getMonthValue());
            totalExpense = appState.getExpenseTotal(ym);
            productRows = loadProductSummary(ym);
        } else {
            LocalDate date = pickDate();
            payments = paymentService.getPaymentsOn(date);
            totalExpense = appState.getExpenseTotal(date);
            productRows = loadProductSummary(date);
        }

        BigDecimal totalSales = sum(payments);
        int orderCount = payments.size();
        BigDecimal netProfit = totalSales.subtract(totalExpense).setScale(2, RoundingMode.HALF_UP);

        totalSalesLbl.setText(MoneyUtil.formatTl(totalSales));
        totalExpenseLbl.setText(MoneyUtil.formatTl(totalExpense));
        netProfitLbl.setText(MoneyUtil.formatTl(netProfit));
        orderCountLbl.setText(String.valueOf(orderCount));
        netProfitLbl.setForeground(netProfit.signum() < 0
                ? new Color(180, 30, 30) : new Color(20, 100, 30));

        // Ödeme yöntemi kırılımı
        EnumMap<PaymentMethod, int[]> countByMethod = new EnumMap<>(PaymentMethod.class);
        EnumMap<PaymentMethod, BigDecimal> sumByMethod = new EnumMap<>(PaymentMethod.class);
        for (Payment p : payments) {
            PaymentMethod m = p.getMethod() == null ? PaymentMethod.CASH : p.getMethod();
            countByMethod.computeIfAbsent(m, k -> new int[]{0})[0]++;
            sumByMethod.merge(m, p.getAmount() == null ? BigDecimal.ZERO : p.getAmount(), BigDecimal::add);
        }
        paymentBreakdownModel.setRowCount(0);
        for (PaymentMethod m : PaymentMethod.values()) {
            int[] c = countByMethod.get(m);
            BigDecimal s = sumByMethod.getOrDefault(m, BigDecimal.ZERO);
            if (c == null && s.signum() == 0) continue;
            paymentBreakdownModel.addRow(new Object[]{
                    describePaymentMethod(m),
                    c == null ? 0 : c[0],
                    MoneyUtil.formatTl(s)
            });
        }
        if (paymentBreakdownModel.getRowCount() == 0) {
            paymentBreakdownModel.addRow(new Object[]{"(kayıt yok)", 0, MoneyUtil.formatTl(BigDecimal.ZERO)});
        }

        // Saatlik kırılım
        int[] perHour = new int[24];
        BigDecimal[] sumPerHour = new BigDecimal[24];
        for (int i = 0; i < 24; i++) sumPerHour[i] = BigDecimal.ZERO;
        for (Payment p : payments) {
            LocalDateTime at = p.getPaidAt();
            if (at == null) continue;
            int h = at.getHour();
            perHour[h]++;
            sumPerHour[h] = sumPerHour[h].add(p.getAmount() == null ? BigDecimal.ZERO : p.getAmount());
        }
        hourlyModel.setRowCount(0);
        for (int h = 0; h < 24; h++) {
            if (perHour[h] == 0 && sumPerHour[h].signum() == 0) continue;
            hourlyModel.addRow(new Object[]{
                    String.format("%02d:00", h),
                    perHour[h],
                    MoneyUtil.formatTl(sumPerHour[h])
            });
        }
        if (hourlyModel.getRowCount() == 0) {
            hourlyModel.addRow(new Object[]{"(saat verisi yok)", 0, MoneyUtil.formatTl(BigDecimal.ZERO)});
        }

        // --- ÜRÜN SATIŞ ÖZETİ ---
        productSummaryModel.setRowCount(0);
        for (ProductSummaryRow r : productRows) {
            String unit = (r.unitLabel == null || r.unitLabel.isBlank())
                    ? (r.piecesPerPortion > 0 ? "şiş" : "porsiyon")
                    : r.unitLabel;
            String soldText = r.totalQty + " " + unit;
            String portionText;
            if (r.piecesPerPortion > 0) {
                double portions = r.portionEquivalent();
                portionText = String.format(new Locale("tr","TR"),
                        "%.2f porsiyon  (1 porsiyon = %d %s)",
                        portions, r.piecesPerPortion, unit);
            } else {
                portionText = r.totalQty + " porsiyon";
            }
            productSummaryModel.addRow(new Object[]{
                    r.productName, unit, soldText, portionText
            });
        }
        if (productSummaryModel.getRowCount() == 0) {
            productSummaryModel.addRow(new Object[]{"(satış yok)", "-", "0", "0"});
        }
    }

    /** Dışarıdan (canlı saat / gün geçişi) çağrılır — bugüne / bu aya dön ve yenile. */
    public void resetToToday() {
        dateSpinner.setValue(new Date());
        monthSpinner.setValue(new Date());
        refresh();
    }

    private LocalDate pickDate() {
        return toLocalDate((Date) dateSpinner.getValue());
    }

    private YearMonth pickMonth() {
        LocalDate ld = toLocalDate((Date) monthSpinner.getValue());
        return YearMonth.of(ld.getYear(), ld.getMonthValue());
    }

    private static LocalDate toLocalDate(Date date) {
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private BigDecimal sum(List<Payment> payments) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Payment p : payments) {
            if (p.getAmount() != null) sum = sum.add(p.getAmount());
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    private String describePaymentMethod(PaymentMethod method) {
        return method.getDisplayName();
    }

    // ---- Excel export ----

    private record ReportMeta(boolean monthly, String periodLabel, String fileBase) {}

    private ReportMeta currentMeta() {
        boolean monthly = "MONTH".equals(mode);
        String periodLabel;
        String fileBase;
        if (monthly) {
            YearMonth ym = pickMonth();
            periodLabel = String.format("%04d-%02d", ym.getYear(), ym.getMonthValue());
            fileBase = "ay-sonu-" + periodLabel;
        } else {
            LocalDate date = pickDate();
            periodLabel = date.toString();
            fileBase = "gun-sonu-" + date;
        }
        return new ReportMeta(monthly, periodLabel, fileBase);
    }

    /** Excel'i in-memory byte dizisi olarak üretir — hem dosya kaydı hem mail eki için. */
    private byte[] buildExcelBytes(ReportMeta meta) throws IOException {
        if (meta.monthly()) {
            return service.report.ReportWorkbookBuilder.buildMonthlyReport(appState, pickMonth());
        }
        return service.report.ReportWorkbookBuilder.buildDailyReport(appState, pickDate());
    }

    private void exportExcel() {
        ReportMeta meta = currentMeta();
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(meta.fileBase() + ".xlsx"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        try {
            byte[] bytes = buildExcelBytes(meta);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
            JOptionPane.showMessageDialog(this,
                    "Rapor kaydedildi:\n" + file.getAbsolutePath(),
                    "Bilgi", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Excel kaydedilemedi: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---- E-posta ile gönderim ----

    private void emailExcel() {
        ReportMeta meta = currentMeta();
        service.email.EmailService emailService = new service.email.EmailService();
        if (!emailService.isConfigured()) {
            JOptionPane.showMessageDialog(this,
                    "SMTP yapılandırması eksik.\n\n" +
                    "Lütfen 'src/main/resources/email-config.properties' dosyasını\n" +
                    "açıp smtp.host, smtp.user ve smtp.password alanlarını doldurun.\n" +
                    "Sonra uygulamayı yeniden başlatın.",
                    "E-posta yapılandırması", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String defaultTo = emailService.defaultRecipient();
        String to = (String) JOptionPane.showInputDialog(this,
                "Alıcı e-posta adresi(leri):\n(birden fazla için virgülle ayırın)",
                "E-posta Gönder", JOptionPane.QUESTION_MESSAGE, null, null,
                defaultTo);
        if (to == null) return;
        to = to.trim();
        if (to.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Alıcı boş olamaz",
                    "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String fileName = meta.fileBase() + ".xlsx";
        String title = meta.monthly() ? "Ay Sonu Raporu" : "Gün Sonu Raporu";
        String subject = "[Dağkapı Ciğercisi] " + title + " — " + meta.periodLabel();
        String body =
                title + "\n" +
                "Dönem: " + meta.periodLabel() + "\n\n" +
                "Toplam Ciro: " + totalSalesLbl.getText() + "\n" +
                "Toplam Gider: " + totalExpenseLbl.getText() + "\n" +
                "Net Kar: " + netProfitLbl.getText() + "\n" +
                "Sipariş Sayısı: " + orderCountLbl.getText() + "\n\n" +
                "Detaylar ekteki Excel dosyasındadır.\n" +
                "— Dağkapı Ciğercisi POS";

        // Büyük gönderim ana iş parçacığını dondurmasın → SwingWorker
        final String finalTo = to;
        SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
            String errorMessage;
            @Override protected Boolean doInBackground() {
                try {
                    byte[] bytes = buildExcelBytes(meta);
                    emailService.sendWithAttachment(finalTo, subject, body, fileName, bytes,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                    return true;
                } catch (Exception ex) {
                    errorMessage = ex.getMessage();
                    return false;
                }
            }
            @Override protected void done() {
                try {
                    if (get()) {
                        JOptionPane.showMessageDialog(DailyReportPanel.this,
                                "Rapor gönderildi:\n" + finalTo,
                                "Bilgi", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(DailyReportPanel.this,
                                "Gönderilemedi: " + errorMessage,
                                "Hata", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(DailyReportPanel.this,
                            "Hata: " + ex.getMessage(),
                            "Hata", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }
}
