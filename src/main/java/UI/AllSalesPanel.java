package UI;

import state.AppState;
import state.SaleRecord;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
        JButton exportButton = new JButton("Dışa aktar");
        exportButton.addActionListener(e -> exportCsv());
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

    private void exportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("sales-" + selectedDate() + ".csv"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            List<SaleRecord> records = appState.getSalesOn(selectedDate());
            try (FileWriter writer = new FileWriter(chooser.getSelectedFile())) {
                writer.write("Masa;Bolum;Tutar;Yontem;Kasiyer;Zaman\n");
                DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
                for (SaleRecord record : records) {
                    writer.write(record.getTableNo() + ";" +
                            record.getBuilding() + " / " + record.getSection() + ";" +
                            record.getTotal().toPlainString() + ";" +
                            (record.getMethod() == null ? "-" : record.getMethod().name()) + ";" +
                            record.getPerformedBy() + ";" +
                            record.getTimestamp().format(timeFormatter) + "\n");
                }
                JOptionPane.showMessageDialog(this, "Dosya kaydedildi", "Bilgi", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Dosya kaydedilemedi: " + e.getMessage(), "Hata", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        appState.removePropertyChangeListener(listener);
    }
}
