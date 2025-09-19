package UI;

import model.User;
import state.AppState;
import state.ExpenseRecord;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
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
    private static final int COL_ID = 0;
    private static final int COL_DATE = 1;
    private static final int COL_DESCRIPTION = 2;
    private static final int COL_AMOUNT = 3;
    private static final int COL_USER = 4;
    private static final int COL_CREATED = 5;
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

    public ExpensesPanel(AppState appState, User currentUser) {
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        setLayout(new BorderLayout(8, 8));

        tableModel = new DefaultTableModel(new Object[]{"ID", "Tarih", "Açıklama", "Tutar", "Kullanıcı", "Kayıt"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> updateDeleteButtonState());
        hideIdColumn();
        add(buildFilterPanel(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildFormPanel(), BorderLayout.SOUTH);

        appState.addPropertyChangeListener(listener);
        reloadExpenses();
    }

    private JComponent buildFilterPanel() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.add(new JLabel("Tarih"));
        filterDateSpinner.setEditor(new JSpinner.DateEditor(filterDateSpinner, "dd-MM-yyyy"));
        toolbar.add(filterDateSpinner);
        JButton refreshButton = new JButton("Listele");
        refreshButton.addActionListener(e -> reloadExpenses());
        toolbar.add(refreshButton);

        deleteButton.setEnabled(false);
        deleteButton.addActionListener(e -> removeSelectedExpense());
        toolbar.add(deleteButton);

        toolbar.addSeparator();
        JButton exportButton = new JButton("Excel'e aktar");
        exportButton.addActionListener(e -> exportToExcel());
        toolbar.add(exportButton);
        return toolbar;
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
            SwingUtilities.invokeLater(this::reloadExpenses);
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
        reloadExpenses();
    }

    private void reloadExpenses() {
        LocalDate date = convertToDate(filterDateSpinner);
        List<ExpenseRecord> records = appState.getExpensesOn(date);
        tableModel.setRowCount(0);
        for (ExpenseRecord record : records) {
            tableModel.addRow(new Object[]{
                    record.getId(),
                    record.getExpenseDate(),
                    record.getDescription(),
                    String.format(Locale.getDefault(), "%.2f", record.getAmount()),
                    record.getPerformedBy(),
                    record.getCreatedAt()
            });
        }
        hideIdColumn();
        updateDeleteButtonState();
    }

    private LocalDate convertToDate(JSpinner spinner) {
        Date date = (Date) spinner.getValue();
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private void hideIdColumn() {
        if (table.getColumnModel().getColumnCount() <= COL_ID) {
            return;
        }
        TableColumn column = table.getColumnModel().getColumn(COL_ID);
        column.setMinWidth(0);
        column.setMaxWidth(0);
        column.setPreferredWidth(0);
        column.setResizable(false);
        TableColumn headerColumn = table.getTableHeader().getColumnModel().getColumn(COL_ID);
        headerColumn.setMinWidth(0);
        headerColumn.setMaxWidth(0);
        headerColumn.setPreferredWidth(0);
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
        if (modelRow < 0 || modelRow >= tableModel.getRowCount()) {
            return;
        }
        Long expenseId = resolveExpenseId(modelRow);
        if (expenseId == null || expenseId <= 0) {
            JOptionPane.showMessageDialog(this, "Seçili gider silinemedi", "Hata", JOptionPane.ERROR_MESSAGE);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Seçili gider silinsin mi?",
                "Onay",
                JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            appState.deleteExpense(expenseId);
            reloadExpenses();
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

    private Long resolveExpenseId(int modelRow) {
        Object value = tableModel.getValueAt(modelRow, COL_ID);
        if (value instanceof Long id) {
            return id;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
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
