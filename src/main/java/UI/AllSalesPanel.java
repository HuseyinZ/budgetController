package UI;

import model.ProductSalesRow;
import state.AppState;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class AllSalesPanel extends JPanel {
    private final AppState appState;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JSpinner dateSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JSpinner timeSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.MINUTE));
    private final JLabel summaryLabel = new JLabel(" ");
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("tr", "TR"));
    private final PropertyChangeListener listener = this::handleStateChange;

    public AllSalesPanel(AppState appState) {
        this.appState = Objects.requireNonNull(appState, "appState");
        setLayout(new BorderLayout(8, 8));

        tableModel = new DefaultTableModel(new Object[]{"Ürün Adı", "Adet Toplamı", "Satır Toplamı (TL)"}, 0) {
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
        panel.add(new JLabel("Saat"));
        timeSpinner.setEditor(new JSpinner.DateEditor(timeSpinner, "HH:mm"));
        panel.add(timeSpinner);
        JButton listButton = new JButton("Listele");
        listButton.addActionListener(e -> refreshTable());
        panel.add(listButton);
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
        List<ProductSalesRow> rows = appState.getProductSalesBefore(threshold);
        tableModel.setRowCount(0);
        BigDecimal total = BigDecimal.ZERO;
        for (ProductSalesRow row : rows) {
            BigDecimal amount = row.getAmountTotal() == null ? BigDecimal.ZERO : row.getAmountTotal();
            total = total.add(amount);
            tableModel.addRow(new Object[]{
                    row.getProductName(),
                    row.getQuantityTotal(),
                    currencyFormat.format(amount)
            });
        }
        updateSummary(rows.size(), total);
    }

    private LocalDateTime selectedDateTime() {
        Date dateValue = (Date) dateSpinner.getValue();
        LocalDate date = Instant.ofEpochMilli(dateValue.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
        Date timeValue = (Date) timeSpinner.getValue();
        LocalTime time = Instant.ofEpochMilli(timeValue.getTime()).atZone(ZoneId.systemDefault()).toLocalTime();
        return LocalDateTime.of(date, time);
    }

    private void updateSummary(int rowCount, BigDecimal total) {
        summaryLabel.setText("Satır: " + rowCount + "   Toplam: " + currencyFormat.format(total));
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        appState.removePropertyChangeListener(listener);
    }
}
