package UI;

import model.PaymentMethod;
import model.ProductSalesRow;
import state.AppState;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    private final JSpinner timeSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.MINUTE));
    private final JLabel summaryLabel = new JLabel(" ");
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("tr", "TR"));
    private final DateTimeFormatter soldAtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final PropertyChangeListener listener = this::handleStateChange;

    public AllSalesPanel(AppState appState) {
        this.appState = Objects.requireNonNull(appState, "appState");
        setLayout(new BorderLayout(8, 8));

        tableModel = new DefaultTableModel(new Object[]{
                "Satış Zamanı",
                "Ürün Adı",
                "Kategori",
                "Adet",
                "Ödeme yönetimi",
                "Satır Toplamı (TL)"
        }, 0) {
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
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "dd-MM-yyyy"));
        panel.add(dateSpinner);
        panel.add(new JLabel("Saat"));
        timeSpinner.setEditor(new JSpinner.DateEditor(timeSpinner, "HH:mm"));
        panel.add(timeSpinner);
        JButton listButton = new JButton("Listele");
        listButton.addActionListener(e -> refreshTable());
        panel.add(listButton);
        JButton exportButton = new JButton("Excel'e aktar");
        exportButton.addActionListener(e -> exportToExcel());
        panel.add(exportButton);
        panel.add(summaryLabel);
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD));
        return panel;
    }

    private void handleStateChange(PropertyChangeEvent event) {
        if (AppState.EVENT_SALES.equals(event.getPropertyName())) {
            SwingUtilities.invokeLater(this::refreshTable);
        }
    }

    private void refreshTable() {
        LocalDateTime threshold = selectedDateTime();
        List<ProductSalesRow> rows = loadRows(threshold);
        tableModel.setRowCount(0);
        BigDecimal total = BigDecimal.ZERO;
        for (ProductSalesRow row : rows) {
            BigDecimal amount = row.getAmountTotal() == null ? BigDecimal.ZERO : row.getAmountTotal();
            total = total.add(amount);
            tableModel.addRow(new Object[]{
                    formatSoldAt(row.getSoldAt()),
                    row.getProductName(),
                    formatCategory(row.getCategoryName()),
                    row.getQuantity(),
                    formatPaymentMethod(row.getPaymentMethod()),
                    currencyFormat.format(amount)
            });
        }
        updateSummary(rows, total);
    }

    private List<ProductSalesRow> loadRows(LocalDateTime threshold) {
        return appState.getProductSalesBefore(threshold);
    }

    private String formatSoldAt(LocalDateTime soldAt) {
        return soldAt == null ? "-" : soldAtFormatter.format(soldAt);
    }

    private LocalDateTime selectedDateTime() {
        Date dateValue = (Date) dateSpinner.getValue();
        LocalDate date = Instant.ofEpochMilli(dateValue.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
        Date timeValue = (Date) timeSpinner.getValue();
        LocalTime time = Instant.ofEpochMilli(timeValue.getTime()).atZone(ZoneId.systemDefault()).toLocalTime();
        return LocalDateTime.of(date, time);
    }

    private void updateSummary(List<ProductSalesRow> rows, BigDecimal total) {
        int rowCount = rows.size();
        int quantityTotal = rows.stream()
                .mapToInt(ProductSalesRow::getQuantity)
                .sum();
        summaryLabel.setText("Satır: " + rowCount + "   Adet: " + quantityTotal + "   Toplam: " + currencyFormat.format(total));
    }

    private void exportToExcel() {
        LocalDateTime threshold = selectedDateTime();
        List<ProductSalesRow> rows = loadRows(threshold);

        JFileChooser chooser = new JFileChooser();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddMMyyyy-HHmm");
        chooser.setSelectedFile(new File("satislar-" + formatter.format(threshold) + ".xlsx"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Satışlar");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Satış Zamanı");
            header.createCell(1).setCellValue("Ürün Adı");
            header.createCell(2).setCellValue("Kategori");
            header.createCell(3).setCellValue("Adet");
            header.createCell(4).setCellValue("Ödeme yönetimi");
            header.createCell(5).setCellValue("Satır Toplamı (TL)");

            int rowIndex = 1;
            int totalQuantity = 0;
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (ProductSalesRow row : rows) {
                Row excelRow = sheet.createRow(rowIndex++);
                excelRow.createCell(0).setCellValue(formatSoldAt(row.getSoldAt()));
                excelRow.createCell(1).setCellValue(row.getProductName());
                excelRow.createCell(2).setCellValue(formatCategory(row.getCategoryName()));
                excelRow.createCell(3).setCellValue(row.getQuantity());
                excelRow.createCell(4).setCellValue(formatPaymentMethod(row.getPaymentMethod()));
                BigDecimal amount = row.getAmountTotal() == null ? BigDecimal.ZERO : row.getAmountTotal();
                excelRow.createCell(5).setCellValue(amount.doubleValue());
                totalQuantity += row.getQuantity();
                totalAmount = totalAmount.add(amount);
            }

            Row summaryRow = sheet.createRow(rowIndex);
            summaryRow.createCell(0).setCellValue("Toplam");
            summaryRow.createCell(3).setCellValue(totalQuantity);
            summaryRow.createCell(5).setCellValue(totalAmount.doubleValue());

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

    private String formatCategory(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return "-";
        }
        return categoryName;
    }

    private String formatPaymentMethod(PaymentMethod method) {
        if (method == null) {
            return "-";
        }
        return switch (method) {
            case CASH -> "Nakit";
            case CREDIT_CARD, CARD -> "Kredi Kartı";
            case DEBIT_CARD -> "Banka Kartı";
            case TRANSFER -> "Havale/EFT";
            case ONLINE -> "Online";
            case MIXED -> "Karma";
        };
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        appState.removePropertyChangeListener(listener);
    }
}
