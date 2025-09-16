package UI.View;

import service.SaleService;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.time.LocalDate;
import java.util.Objects;

public class SaleView extends JFrame {
    private final SaleService saleService;
    private final JLabel dailyTotalLabel = new JLabel(" ");
    private final JLabel monthlyTotalLabel = new JLabel(" ");
    private final JSpinner dateSpinner;
    private final JSpinner monthSpinner;
    private final JSpinner yearSpinner;

    public SaleView() {
        this(new SaleService());
    }

    public SaleView(SaleService saleService) {
        super("Satış Panosu");
        this.saleService = Objects.requireNonNull(saleService, "saleService");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.anchor = GridBagConstraints.WEST;

        dateSpinner = new JSpinner(new SpinnerDateModel(new java.util.Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "yyyy-MM-dd"));
        monthSpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getMonthValue(), 1, 12, 1));
        yearSpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getYear(), 2000, 2100, 1));

        int row = 0;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Günlük tarih"), gc);
        gc.gridx = 1;
        panel.add(dateSpinner, gc);
        JButton refreshDaily = new JButton("Günlük toplamı hesapla");
        gc.gridx = 2;
        panel.add(refreshDaily, gc);
        gc.gridx = 3;
        panel.add(dailyTotalLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Ay / Yıl"), gc);
        JPanel monthPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        monthPanel.add(monthSpinner);
        monthPanel.add(yearSpinner);
        gc.gridx = 1;
        panel.add(monthPanel, gc);
        JButton refreshMonthly = new JButton("Aylık toplamı hesapla");
        gc.gridx = 2;
        panel.add(refreshMonthly, gc);
        gc.gridx = 3;
        panel.add(monthlyTotalLabel, gc);

        row++;
        gc.gridx = 2; gc.gridy = row; gc.gridwidth = 2;
        JButton exportButton = new JButton("Aylık raporu dışa aktar");
        panel.add(exportButton, gc);

        add(panel, BorderLayout.CENTER);

        refreshDaily.addActionListener(e -> updateDailyTotal());
        refreshMonthly.addActionListener(e -> updateMonthlyTotal());
        exportButton.addActionListener(e -> exportMonthlyReport());

        updateDailyTotal();
        updateMonthlyTotal();
        pack();
        setLocationRelativeTo(null);
    }

    private void updateDailyTotal() {
        LocalDate date = ((java.util.Date) dateSpinner.getValue()).toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        dailyTotalLabel.setText(saleService.getDailySalesTotal(date).toPlainString());
        dailyTotalLabel.setForeground(Color.DARK_GRAY);
    }

    private void updateMonthlyTotal() {
        int month = (int) monthSpinner.getValue();
        int year = (int) yearSpinner.getValue();
        monthlyTotalLabel.setText(saleService.getMonthlySalesTotal(year, month).toPlainString());
        monthlyTotalLabel.setForeground(Color.DARK_GRAY);
    }

    private void exportMonthlyReport() {
        int month = (int) monthSpinner.getValue();
        int year = (int) yearSpinner.getValue();
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("MonthlySales-" + year + "-" + month + ".xlsx"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            boolean ok = saleService.exportMonthlySalesReport(year, month, chooser.getSelectedFile().getAbsolutePath());
            monthlyTotalLabel.setText(ok ? "Rapor kaydedildi" : "Rapor oluşturulamadı");
            monthlyTotalLabel.setForeground(ok ? new Color(0, 128, 0) : Color.RED.darker());
        }
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
