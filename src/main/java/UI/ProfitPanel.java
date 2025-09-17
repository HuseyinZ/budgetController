package UI;

import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.Date;
import java.util.Objects;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class ProfitPanel extends JPanel {
    private final AppState appState;
    private final JSpinner dailyDateSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JSpinner monthSpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getMonthValue(), 1, 12, 1));
    private final JSpinner yearSpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getYear(), 2000, 2100, 1));
    private final JLabel dailySalesLabel = new JLabel("0.00");
    private final JLabel dailyExpenseLabel = new JLabel("0.00");
    private final JLabel dailyNetLabel = new JLabel("0.00");
    private final JLabel monthlySalesLabel = new JLabel("0.00");
    private final JLabel monthlyExpenseLabel = new JLabel("0.00");
    private final JLabel monthlyNetLabel = new JLabel("0.00");
    private final PropertyChangeListener listener = this::handleStateChange;

    public ProfitPanel(AppState appState) {
        this.appState = Objects.requireNonNull(appState, "appState");
        setLayout(new BorderLayout(8, 8));
        add(buildContent(), BorderLayout.CENTER);
        appState.addPropertyChangeListener(listener);
        updateDailyTotals();
        updateMonthlyTotals();
    }

    private JComponent buildContent() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(8, 8, 8, 8);
        gc.anchor = GridBagConstraints.WEST;

        int row = 0;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Günlük tarih"), gc);
        dailyDateSpinner.setEditor(new JSpinner.DateEditor(dailyDateSpinner, "yyyy-MM-dd"));
        gc.gridx = 1;
        panel.add(dailyDateSpinner, gc);
        JButton refreshDaily = new JButton("Güncelle");
        refreshDaily.addActionListener(e -> updateDailyTotals());
        gc.gridx = 2;
        panel.add(refreshDaily, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Günlük Satış"), gc);
        gc.gridx = 1;
        panel.add(dailySalesLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Günlük Gider"), gc);
        gc.gridx = 1;
        panel.add(dailyExpenseLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Günlük Net"), gc);
        dailyNetLabel.setFont(dailyNetLabel.getFont().deriveFont(Font.BOLD));
        gc.gridx = 1;
        panel.add(dailyNetLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Ay / Yıl"), gc);
        JPanel monthPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        monthPanel.add(monthSpinner);
        monthPanel.add(yearSpinner);
        gc.gridx = 1;
        panel.add(monthPanel, gc);
        JButton refreshMonthly = new JButton("Güncelle");
        refreshMonthly.addActionListener(e -> updateMonthlyTotals());
        gc.gridx = 2;
        panel.add(refreshMonthly, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Aylık Satış"), gc);
        gc.gridx = 1;
        panel.add(monthlySalesLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Aylık Gider"), gc);
        gc.gridx = 1;
        panel.add(monthlyExpenseLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Aylık Net"), gc);
        monthlyNetLabel.setFont(monthlyNetLabel.getFont().deriveFont(Font.BOLD));
        gc.gridx = 1;
        panel.add(monthlyNetLabel, gc);

        row++;
        gc.gridx = 2; gc.gridy = row; gc.anchor = GridBagConstraints.EAST;
        JButton exportButton = new JButton("Excel'e aktar");
        exportButton.addActionListener(e -> exportToExcel());
        panel.add(exportButton, gc);
        gc.anchor = GridBagConstraints.WEST;

        return panel;
    }

    private void handleStateChange(PropertyChangeEvent event) {
        if (AppState.EVENT_SALES.equals(event.getPropertyName()) || AppState.EVENT_EXPENSES.equals(event.getPropertyName())) {
            SwingUtilities.invokeLater(() -> {
                updateDailyTotals();
                updateMonthlyTotals();
            });
        }
    }

    private void updateDailyTotals() {
        LocalDate date = convertToDate(dailyDateSpinner);
        BigDecimal sales = appState.getSalesTotal(date);
        BigDecimal expenses = appState.getExpenseTotal(date);
        BigDecimal net = appState.getNetProfit(date);
        dailySalesLabel.setText(format(sales));
        dailyExpenseLabel.setText(format(expenses));
        dailyNetLabel.setText(format(net));
        dailyNetLabel.setForeground(net.signum() >= 0 ? new Color(46, 125, 50) : Color.RED.darker());
    }

    private void updateMonthlyTotals() {
        YearMonth month = YearMonth.of((int) yearSpinner.getValue(), (int) monthSpinner.getValue());
        BigDecimal sales = appState.getSalesTotal(month);
        BigDecimal expenses = appState.getExpenseTotal(month);
        BigDecimal net = appState.getNetProfit(month);
        monthlySalesLabel.setText(format(sales));
        monthlyExpenseLabel.setText(format(expenses));
        monthlyNetLabel.setText(format(net));
        monthlyNetLabel.setForeground(net.signum() >= 0 ? new Color(46, 125, 50) : Color.RED.darker());
    }

    private String format(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private LocalDate convertToDate(JSpinner spinner) {
        Date date = (Date) spinner.getValue();
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private void exportToExcel() {
        LocalDate dailyDate = convertToDate(dailyDateSpinner);
        YearMonth month = YearMonth.of((int) yearSpinner.getValue(), (int) monthSpinner.getValue());

        BigDecimal dailySales = appState.getSalesTotal(dailyDate);
        BigDecimal dailyExpenses = appState.getExpenseTotal(dailyDate);
        BigDecimal dailyNet = appState.getNetProfit(dailyDate);

        BigDecimal monthlySales = appState.getSalesTotal(month);
        BigDecimal monthlyExpenses = appState.getExpenseTotal(month);
        BigDecimal monthlyNet = appState.getNetProfit(month);

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("net-kar-" + month + ".xlsx"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Net Kar");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Dönem");
            header.createCell(1).setCellValue("Satış");
            header.createCell(2).setCellValue("Gider");
            header.createCell(3).setCellValue("Net Kar");

            Row dailyRow = sheet.createRow(1);
            dailyRow.createCell(0).setCellValue("Günlük (" + dailyDate + ")");
            dailyRow.createCell(1).setCellValue(dailySales.doubleValue());
            dailyRow.createCell(2).setCellValue(dailyExpenses.doubleValue());
            dailyRow.createCell(3).setCellValue(dailyNet.doubleValue());

            Row monthlyRow = sheet.createRow(2);
            monthlyRow.createCell(0).setCellValue("Aylık (" + month + ")");
            monthlyRow.createCell(1).setCellValue(monthlySales.doubleValue());
            monthlyRow.createCell(2).setCellValue(monthlyExpenses.doubleValue());
            monthlyRow.createCell(3).setCellValue(monthlyNet.doubleValue());

            for (int i = 0; i < 4; i++) {
                sheet.autoSizeColumn(i);
            }

            try (FileOutputStream out = new FileOutputStream(file)) {
                workbook.write(out);
            }
            JOptionPane.showMessageDialog(this, "Excel dosyası kaydedildi", "Bilgi", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Excel kaydedilemedi: " + ex.getMessage(), "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        appState.removePropertyChangeListener(listener);
    }
}
