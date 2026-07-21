package UI;

import model.Payment;
import service.PaymentService;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Saatlik yoğunluk grafiği — seçili gün için 24 saatlik bar chart.
 *
 * <p>X ekseninde saatler (00..23), Y ekseninde o saatteki işlem (satış) sayısı.
 * Mouse hover ile tutar gösterilir. JFreeChart bağımlılığı yok — saf Swing 2D.
 *
 * <p>Yalnız ADMIN erişebilir (DashboardView'da filtrelenir).
 */
public class HourlyHeatmapPanel extends JPanel {

    private static final DateTimeFormatter HEADER_FMT = DateTimeFormatter.ofPattern("dd MMMM yyyy", new Locale("tr","TR"));

    private final AppState appState;
    private final PaymentService paymentService;

    private final JSpinner dateSpinner = new JSpinner(
            new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final ChartCanvas canvas = new ChartCanvas();
    private final JLabel headerLabel = new JLabel();
    private final JToggleButton countModeBtn = new JToggleButton("Adet", true);
    private final JToggleButton amountModeBtn = new JToggleButton("Tutar (₺)");

    public HourlyHeatmapPanel(AppState appState) {
        this.appState = appState;
        this.paymentService = new PaymentService();
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(buildToolbar(), BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        refresh();
    }

    private JComponent buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(new JLabel("Tarih: "));
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "dd-MM-yyyy"));
        dateSpinner.setPreferredSize(new Dimension(160, 36));
        bar.add(dateSpinner);
        JButton refresh = new JButton("Yenile");
        refresh.setPreferredSize(new Dimension(120, 36));
        refresh.addActionListener(e -> refresh());
        bar.add(refresh);
        JButton today = new JButton("Bugün");
        today.addActionListener(e -> { dateSpinner.setValue(new Date()); refresh(); });
        bar.add(today);
        bar.addSeparator();

        // Önceki/Sonraki gün
        JButton prev = new JButton("◀ Önceki Gün");
        prev.addActionListener(e -> shiftDate(-1));
        JButton next = new JButton("Sonraki Gün ▶");
        next.addActionListener(e -> shiftDate(1));
        bar.add(prev);
        bar.add(next);

        // Görünüm modu — Adet | Tutar
        bar.addSeparator();
        bar.add(new JLabel("Görünüm: "));
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(countModeBtn);
        modeGroup.add(amountModeBtn);
        countModeBtn.setPreferredSize(new Dimension(100, 36));
        amountModeBtn.setPreferredSize(new Dimension(120, 36));
        countModeBtn.addActionListener(e -> {
            canvas.setMode(ChartCanvas.Mode.COUNT);
        });
        amountModeBtn.addActionListener(e -> {
            canvas.setMode(ChartCanvas.Mode.AMOUNT);
        });
        bar.add(countModeBtn);
        bar.add(amountModeBtn);
        return bar;
    }

    private JComponent buildFooter() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 13f));
        p.add(headerLabel);
        return p;
    }

    /** Dışarıdan (canlı saat / gün geçişi) çağrılır — bugüne dön ve yenile. */
    public void resetToToday() {
        dateSpinner.setValue(new Date());
        refresh();
    }

    private LocalDate toLocalDate(Date date) {
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private void shiftDate(int days) {
        Date d = (Date) dateSpinner.getValue();
        LocalDate ld = toLocalDate(d).plusDays(days);
        dateSpinner.setValue(Date.from(ld.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        refresh();
    }

    private void refresh() {
        Date d = (Date) dateSpinner.getValue();
        LocalDate date = toLocalDate(d);
        List<Payment> payments = paymentService.getPaymentsOn(date);

        int[] counts = new int[24];
        double[] amounts = new double[24];
        int total = 0;
        double totalAmount = 0;
        for (Payment p : payments) {
            LocalDateTime at = p.getPaidAt();
            if (at == null) continue;
            int h = at.getHour();
            counts[h]++;
            if (p.getAmount() != null) {
                double a = p.getAmount().doubleValue();
                amounts[h] += a;
                totalAmount += a;
            }
            total++;
        }
        canvas.setData(counts, amounts);
        headerLabel.setText(
                date.format(HEADER_FMT) + " — Toplam " + total + " sipariş, "
              + String.format(new Locale("tr","TR"), "%,.2f ₺", totalAmount));
    }

    // ------ ÇİZİM ------
    private static class ChartCanvas extends JComponent {
        enum Mode { COUNT, AMOUNT }

        private int[] counts = new int[24];
        private double[] amounts = new double[24];
        private Mode mode = Mode.COUNT;

        ChartCanvas() {
            setPreferredSize(new Dimension(800, 380));
            setBackground(Color.WHITE);
            ToolTipManager.sharedInstance().registerComponent(this);
            // Mouse over → tooltip (her iki bilgi de gösterilir)
            addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override
                public void mouseMoved(java.awt.event.MouseEvent e) {
                    int hour = hourAtX(e.getX());
                    if (hour < 0 || hour > 23) { setToolTipText(null); return; }
                    setToolTipText(String.format("<html><b>%02d:00 - %02d:00</b><br/>%d işlem<br/>%,.2f ₺</html>",
                            hour, (hour+1)%24, counts[hour], amounts[hour]));
                }
            });
        }

        void setData(int[] c, double[] a) {
            this.counts = c.clone();
            this.amounts = a.clone();
            repaint();
        }

        void setMode(Mode m) {
            if (m != null && m != this.mode) {
                this.mode = m;
                repaint();
            }
        }

        private int hourAtX(int x) {
            int padLeft = 60;
            int w = getWidth() - padLeft - 20;
            if (w <= 0) return -1;
            double barW = w / 24.0;
            int hr = (int) ((x - padLeft) / barW);
            return (hr < 0 || hr > 23) ? -1 : hr;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int padLeft = 70, padRight = 20, padTop = 20, padBot = 50;
            int w = getWidth() - padLeft - padRight;
            int h = getHeight() - padTop - padBot;

            // Arka plan
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, getWidth(), getHeight());

            // Aktif moda göre veri dizisi seç
            boolean amountMode = (mode == Mode.AMOUNT);
            double[] values = new double[24];
            for (int i = 0; i < 24; i++) {
                values[i] = amountMode ? amounts[i] : (double) counts[i];
            }
            double maxVal = 1.0;
            for (double v : values) if (v > maxVal) maxVal = v;

            // Renk paleti — adet=mavi, tutar=yeşil
            Color colorLight = amountMode ? new Color(220, 245, 220) : new Color(220, 235, 252);
            Color colorDark  = amountMode ? new Color(46, 125, 50)   : new Color(20, 100, 200);
            Color textColor  = amountMode ? new Color(27, 94, 32)    : new Color(20, 60, 100);

            // Y ekseni grid + etiketler
            int gridSteps = 5;
            g2.setFont(g2.getFont().deriveFont(11f));
            for (int i = 0; i <= gridSteps; i++) {
                int y = padTop + (int) (h * (1.0 - i / (double) gridSteps));
                g2.setColor(new Color(230, 230, 230));
                g2.drawLine(padLeft, y, padLeft + w, y);
                g2.setColor(Color.DARK_GRAY);
                double stepValue = maxVal * i / (double) gridSteps;
                String label = amountMode
                        ? formatShortCurrency(stepValue)
                        : String.valueOf((int) Math.round(stepValue));
                g2.drawString(label, padLeft - 60, y + 4);
            }

            // X ekseni saat etiketleri + barlar
            double barWidth = w / 24.0;
            for (int hr = 0; hr < 24; hr++) {
                double xBar = padLeft + hr * barWidth;
                double barH = maxVal == 0 ? 0 : h * (values[hr] / maxVal);
                double y = padTop + h - barH;

                float intensity = (float) (maxVal == 0 ? 0 : values[hr] / maxVal);
                Color barColor = blend(colorLight, colorDark, intensity);
                g2.setColor(barColor);
                Rectangle2D.Double rect = new Rectangle2D.Double(
                        xBar + 2, y, Math.max(0, barWidth - 4), barH);
                g2.fill(rect);
                g2.setColor(colorDark.darker());
                g2.draw(rect);

                // X ekseni etiketi (her 2 saatte bir)
                if (hr % 2 == 0) {
                    g2.setColor(Color.DARK_GRAY);
                    String label = String.format("%02d", hr);
                    g2.drawString(label, (int) (xBar + barWidth/2 - 8), padTop + h + 16);
                }

                // Bar üzerinde değer (yüksekse)
                if (values[hr] > 0) {
                    g2.setColor(textColor);
                    String t = amountMode
                            ? formatShortCurrency(values[hr])
                            : String.valueOf((int) values[hr]);
                    int tx = (int) (xBar + barWidth/2 - g2.getFontMetrics().stringWidth(t)/2.0);
                    int ty = (int) Math.max(padTop + 12, y - 4);
                    g2.drawString(t, tx, ty);
                }
            }

            // Eksen çizgileri
            g2.setColor(Color.GRAY);
            g2.drawLine(padLeft, padTop, padLeft, padTop + h);
            g2.drawLine(padLeft, padTop + h, padLeft + w, padTop + h);

            // X başlık
            g2.setColor(Color.DARK_GRAY);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
            g2.drawString("Saat", padLeft + w / 2 - 12, padTop + h + 36);
            // Y başlık (90 derece döndürülmüş)
            java.awt.geom.AffineTransform old = g2.getTransform();
            g2.rotate(-Math.PI / 2);
            String yTitle = amountMode ? "Satış Tutarı (₺)" : "İşlem Sayısı";
            g2.drawString(yTitle, -(padTop + h/2 + 40), 14);
            g2.setTransform(old);

            g2.dispose();
        }

        /** "12.5K" veya "750" gibi kısa para formatı (eksen etiketleri için). */
        private String formatShortCurrency(double v) {
            if (v >= 1_000_000) return String.format(Locale.US, "%.1fM", v / 1_000_000);
            if (v >= 1_000)     return String.format(Locale.US, "%.1fK", v / 1_000);
            return String.format(Locale.US, "%.0f", v);
        }

        private Color blend(Color a, Color b, float t) {
            t = Math.max(0f, Math.min(1f, t));
            int r = (int) (a.getRed()   + (b.getRed()   - a.getRed())   * t);
            int g = (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t);
            int bl= (int) (a.getBlue()  + (b.getBlue()  - a.getBlue())  * t);
            return new Color(r, g, bl);
        }
    }
}
