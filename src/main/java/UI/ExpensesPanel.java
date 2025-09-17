package UI;

import model.User;
import state.AppState;
import state.ExpenseRecord;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class ExpensesPanel extends JPanel {
    private final AppState appState;
    private final User currentUser;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JTextField descriptionField = new JTextField(20);
    private final JSpinner amountSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 1_000_000.0, 5.0));
    private final JSpinner filterDateSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JSpinner expenseDateSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final PropertyChangeListener listener = this::handleStateChange;

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
        add(buildFilterPanel(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildFormPanel(), BorderLayout.SOUTH);

        appState.addPropertyChangeListener(listener);
        refreshTable();
    }

    private JComponent buildFilterPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.add(new JLabel("Tarih"));
        filterDateSpinner.setEditor(new JSpinner.DateEditor(filterDateSpinner, "yyyy-MM-dd"));
        panel.add(filterDateSpinner);
        JButton refreshButton = new JButton("Listele");
        refreshButton.addActionListener(e -> refreshTable());
        panel.add(refreshButton);
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
        expenseDateSpinner.setEditor(new JSpinner.DateEditor(expenseDateSpinner, "yyyy-MM-dd"));
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
    }

    private LocalDate convertToDate(JSpinner spinner) {
        Date date = (Date) spinner.getValue();
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        appState.removePropertyChangeListener(listener);
    }
}
