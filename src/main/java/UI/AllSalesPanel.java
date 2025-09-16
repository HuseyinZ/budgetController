package UI;

import model.Payment;
import service.SaleService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Objects;

public class AllSalesPanel extends JPanel {
    private final SaleService saleService;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JLabel totalLabel = new JLabel(" ");
    private final JSpinner dateSpinner;

    public AllSalesPanel() {
        this(new SaleService());
    }

    public AllSalesPanel(SaleService saleService) {
        this.saleService = Objects.requireNonNull(saleService, "saleService");
        setLayout(new BorderLayout(8, 8));

        tableModel = new DefaultTableModel(new Object[]{"Sipariş", "Tutar", "Yöntem", "Kasiyer", "Ödeme Zamanı"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING));
        controls.add(new JLabel("Tarih"));
        dateSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "yyyy-MM-dd"));
        JButton loadButton = new JButton("Listele");
        JButton exportButton = new JButton("Excel'e aktar");
        controls.add(dateSpinner);
        controls.add(loadButton);
        controls.add(exportButton);
        controls.add(totalLabel);
        totalLabel.setForeground(Color.DARK_GRAY);
        add(controls, BorderLayout.NORTH);

        loadButton.addActionListener(e -> loadData());
        exportButton.addActionListener(e -> exportReport());

        loadData();
    }

    private LocalDate selectedDate() {
        Date date = (Date) dateSpinner.getValue();
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private void loadData() {
        LocalDate date = selectedDate();
        List<Payment> payments = saleService.getDailySalesList(date);
        tableModel.setRowCount(0);
        payments.forEach(payment -> tableModel.addRow(new Object[]{
                payment.getOrderId(),
                payment.getAmount() == null ? "0.00" : payment.getAmount().toPlainString(),
                payment.getMethod(),
                payment.getCashierId(),
                payment.getPaidAt()
        }));
        totalLabel.setText("Toplam: " + saleService.getDailySalesTotal(date).toPlainString());
    }

    private void exportReport() {
        LocalDate date = selectedDate();
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("DailySales-" + date + ".xlsx"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            boolean ok = saleService.exportDailySalesReport(date, file.getAbsolutePath());
            totalLabel.setText(ok ? "Rapor kaydedildi: " + file : "Rapor oluşturulamadı");
            totalLabel.setForeground(ok ? new Color(0, 128, 0) : Color.RED.darker());
        }
    }
}
