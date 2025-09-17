package UI;

import state.AppState;
import state.SaleRecord;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class AllSalesPanel extends JPanel {
    private final AppState appState;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JSpinner dateSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JLabel totalLabel = new JLabel("0.00");
    private final PropertyChangeListener listener = this::handleStateChange;

    public AllSalesPanel(AppState appState) {
        this.appState = Objects.requireNonNull(appState, "appState");
        setLayout(new BorderLayout(8, 8));

        tableModel = new DefaultTableModel(new Object[]{"Masa", "Bölüm", "Tutar", "Yöntem", "Kasiyer", "Zaman"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setRowHeight(22);
        add(buildControls(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        appState.addPropertyChangeListener(listener);
        refreshTable();
    }

    private JComponent buildControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.add(new JLabel("Tarih"));
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "yyyy-MM-dd"));
        panel.add(dateSpinner);
        JButton listButton = new JButton("Listele");
        listButton.addActionListener(e -> refreshTable());
        panel.add(listButton);
        JButton exportButton = new JButton("Excel'e aktar");
        exportButton.addActionListener(e -> exportToExcel());
        panel.add(exportButton);
        panel.add(totalLabel);
        totalLabel.setFont(totalLabel.getFont().deriveFont(Font.BOLD));
        return panel;
    }

    private void handleStateChange(PropertyChangeEvent event) {
        if (AppState.EVENT_SALES.equals(event.getPropertyName())) {
            SwingUtilities.invokeLater(this::refreshTable);
        }
    }

    private void refreshTable() {
        LocalDate date = selectedDate();
        List<SaleRecord> records = appState.getSalesOn(date);
        tableModel.setRowCount(0);
        double total = 0;
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
        for (SaleRecord record : records) {
            double amount = record.getTotal().doubleValue();
            total += amount;
            tableModel.addRow(new Object[]{
                    record.getTableNo(),
                    record.getBuilding() + " / " + record.getSection(),
                    String.format(Locale.getDefault(), "%.2f", amount),
                    record.getMethod() == null ? "-" : record.getMethod().name(),
                    record.getPerformedBy(),
                    record.getTimestamp().format(timeFormatter)
            });
        }
        totalLabel.setText("Toplam: " + String.format(Locale.getDefault(), "%.2f", total));
    }

    private LocalDate selectedDate() {
        Date date = (Date) dateSpinner.getValue();
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private void exportToExcel() {
        LocalDate date = selectedDate();
        List<SaleRecord> records = appState.getSalesOn(date);

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("satislar-" + date + ".xlsx"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Satışlar");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Masa");
            header.createCell(1).setCellValue("Bölüm");
            header.createCell(2).setCellValue("Tutar");
            header.createCell(3).setCellValue("Yöntem");
            header.createCell(4).setCellValue("Kasiyer");
            header.createCell(5).setCellValue("Zaman");

            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
            int rowIndex = 1;
            for (SaleRecord record : records) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(record.getTableNo());
                row.createCell(1).setCellValue(record.getBuilding() + " / " + record.getSection());
                row.createCell(2).setCellValue(record.getTotal().doubleValue());
                row.createCell(3).setCellValue(record.getMethod() == null ? "-" : record.getMethod().name());
                row.createCell(4).setCellValue(record.getPerformedBy());
                row.createCell(5).setCellValue(record.getTimestamp().format(timeFormatter));
            }

            for (int i = 0; i < 6; i++) {
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
