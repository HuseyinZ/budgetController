package UI;

import model.User;
import state.AppState;
import state.ExpenseRecord;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class ExpensesPanel extends JPanel {
    private final AppState appState;
    private final User currentUser;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JTextField descriptionField = new JTextField(20);
    private final JSpinner amountSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 1_000_000.0, 5.0));
    private final JSpinner filterDateSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JSpinner expenseDateSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JButton deleteButton = new JButton("Gider kaldır");
    private final PropertyChangeListener listener = this::handleStateChange;
    private List<ExpenseRecord> currentRecords = List.of();

    public ExpensesPanel(AppState appState, User currentUser) {
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        setLayout(new BorderLayout(8, 8));

        tableModel = new DefaultTableModel(new Object[]{"Tarih", "Açıklama", "Tutar", "Kullanıcı", "Kayıt"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> updateDeleteButtonState());
        add(buildFilterPanel(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildFormPanel(), BorderLayout.SOUTH);

        appState.addPropertyChangeListener(listener);
        refreshTable();
    }

    private JComponent buildFilterPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.add(new JLabel("Tarih"));
        filterDateSpinner.setEditor(new JSpinner.DateEditor(filterDateSpinner, "dd-MM-yyyy"));
        panel.add(filterDateSpinner);
        JButton refreshButton = new JButton("Listele");
        refreshButton.addActionListener(e -> refreshTable());
        panel.add(refreshButton);

        deleteButton.setEnabled(false);
        deleteButton.addActionListener(e -> removeSelectedExpense());
        panel.add(deleteButton);

        JButton exportButton = new JButton("Excel'e aktar");
        exportButton.addActionListener(e -> exportToExcel());
        panel.add(exportButton);
        return panel;
    }

    private JComponent buildFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;

        int row = 0;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Gider Tarihi"), gc);
        expenseDateSpinner.setEditor(new JSpinner.DateEditor(expenseDateSpinner, "dd-MM-yyyy")); //yyyy-MM-dd
        gc.gridx = 1;
        panel.add(expenseDateSpinner, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Açıklama"), gc);
        gc.gridx = 1;
        panel.add(descriptionField, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Tutar"), gc);
        amountSpinner.setEditor(new JSpinner.NumberEditor(amountSpinner, "#,##0.00"));
        gc.gridx = 1;
        panel.add(amountSpinner, gc);

        row++;
        gc.gridx = 1; gc.gridy = row; gc.anchor = GridBagConstraints.EAST;
        JButton addButton = new JButton("Ekle");
        addButton.addActionListener(e -> addExpense());
        panel.add(addButton, gc);

        return panel;
    }

    private void handleStateChange(PropertyChangeEvent event) {
        if (AppState.EVENT_EXPENSES.equals(event.getPropertyName())) {
            SwingUtilities.invokeLater(this::refreshTable);
        }
    }

    private void addExpense() {
        String description = descriptionField.getText().trim();
        if (description.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Açıklama gerekli", "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        double amountValue = ((Number) amountSpinner.getValue()).doubleValue();
        if (amountValue <= 0) {
            JOptionPane.showMessageDialog(this, "Tutar sıfırdan büyük olmalı", "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        LocalDate date = convertToDate(expenseDateSpinner);
        appState.addExpense(BigDecimal.valueOf(amountValue), description, date, currentUser);
        descriptionField.setText("");
        amountSpinner.setValue(0.0);
        refreshTable();
    }

    private void refreshTable() {
        LocalDate date = convertToDate(filterDateSpinner);
        List<ExpenseRecord> records = appState.getExpensesOn(date);
        currentRecords = List.copyOf(records);
        tableModel.setRowCount(0);
        for (ExpenseRecord record : records) {
            tableModel.addRow(new Object[]{
                    record.getExpenseDate(),
                    record.getDescription(),
                    String.format(Locale.getDefault(), "%.2f", record.getAmount()),
                    record.getPerformedBy(),
                    record.getCreatedAt()
            });
        }
        updateDeleteButtonState();
    }

    private LocalDate convertToDate(JSpinner spinner) {
        Date date = (Date) spinner.getValue();
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private void updateDeleteButtonState() {
        boolean enabled = table.getSelectedRow() >= 0 && table.getSelectedRow() < tableModel.getRowCount();
        deleteButton.setEnabled(enabled);
    }

    private void removeSelectedExpense() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= currentRecords.size()) {
            return;
        }
        ExpenseRecord record = currentRecords.get(modelRow);
        Long id = record.getId();
        if (id == null || id <= 0) {
            JOptionPane.showMessageDialog(this, "Seçili gider silinemedi", "Hata", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String description = record.getDescription();
        if (description == null || description.isBlank()) {
            description = "seçili gider";
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                description + " kaydını silmek istiyor musunuz?",
                "Onay",
                JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            appState.deleteExpense(id);
            refreshTable();
            table.clearSelection();
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            if (message == null || message.isBlank()) {
                message = "Gider silinemedi.";
            }
            JOptionPane.showMessageDialog(this, message, "Hata", JOptionPane.ERROR_MESSAGE);
        } finally {
            updateDeleteButtonState();
        }
    }

    private void exportToExcel() {
        LocalDate date = convertToDate(filterDateSpinner);
        List<ExpenseRecord> records = appState.getExpensesOn(date);

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("giderler-" + date + ".xlsx"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File file = chooser.getSelectedFile();
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Giderler");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Tarih");
            header.createCell(1).setCellValue("Açıklama");
            header.createCell(2).setCellValue("Tutar");
            header.createCell(3).setCellValue("Kullanıcı");
            header.createCell(4).setCellValue("Kayıt Zamanı");

            int rowIndex = 1;
            for (ExpenseRecord record : records) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(record.getExpenseDate().toString());
                row.createCell(1).setCellValue(record.getDescription());
                row.createCell(2).setCellValue(record.getAmount().doubleValue());
                row.createCell(3).setCellValue(record.getPerformedBy());
                row.createCell(4).setCellValue(record.getCreatedAt().toString());
            }

            for (int i = 0; i < 5; i++) {
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
