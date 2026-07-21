package UI;

import model.MoneyUtil;
import model.RefundLog;
import state.AppState;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * İşlem Geçmişi paneli — iade/iptal/silme audit log'larını gösterir.
 *
 * <p>Tarih + kullanıcı + işlem tipi filtresiyle tablo görünümü. Sadece ADMIN
 * görür; veriler {@code refund_log} tablosundan okunur.
 *
 * <p>Renkler:
 * <ul>
 *   <li>CLEAR_TABLE: kırmızı (en ciddi)</li>
 *   <li>REMOVE_ITEM: turuncu</li>
 *   <li>DECREASE_ITEM: sarı</li>
 *   <li>CANCEL_ORDER: koyu kırmızı</li>
 * </ul>
 */
public class RefundHistoryPanel extends JPanel {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final NumberFormat MONEY_FMT =
            NumberFormat.getCurrencyInstance(new Locale("tr", "TR"));

    private final AppState appState;
    private final LogTableModel tableModel = new LogTableModel();
    private final JTable table = new JTable(tableModel);

    private final JComboBox<DateRangeOption> rangeCombo = new JComboBox<>(DateRangeOption.values());
    private final JComboBox<String> actionCombo = new JComboBox<>(new String[] {
            "Tümü", "CLEAR_TABLE", "REMOVE_ITEM", "DECREASE_ITEM", "CANCEL_ORDER"
    });
    private final JTextField userFilter = new JTextField(14);
    private final JLabel summaryLabel = new JLabel(" ");

    public RefundHistoryPanel(AppState appState) {
        this.appState = appState;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        reload();
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        header.setBorder(BorderFactory.createTitledBorder("Filtre"));

        header.add(new JLabel("Tarih:"));
        rangeCombo.setSelectedIndex(0);
        rangeCombo.addActionListener(e -> reload());
        header.add(rangeCombo);

        header.add(new JLabel("  İşlem:"));
        actionCombo.addActionListener(e -> applyFilters());
        header.add(actionCombo);

        header.add(new JLabel("  Kullanıcı:"));
        userFilter.setToolTipText("Kullanıcı adının bir kısmını yazın");
        userFilter.addActionListener(e -> applyFilters());
        header.add(userFilter);
        JButton applyBtn = new JButton("Uygula");
        applyBtn.addActionListener(e -> applyFilters());
        header.add(applyBtn);

        JButton refreshBtn = new JButton("Yenile");
        refreshBtn.addActionListener(e -> reload());
        header.add(refreshBtn);

        return header;
    }

    private JComponent buildTable() {
        table.setRowHeight(28);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setFont(table.getFont().deriveFont(13f));
        table.getTableHeader().setFont(table.getTableHeader().getFont().deriveFont(Font.BOLD, 13f));

        // Renk kodlu satırlar — action_type sütununa göre
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                                                            boolean hasFocus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, col);
                if (!isSelected) {
                    RefundLog log = tableModel.rowAt(t.convertRowIndexToModel(row));
                    if (log != null && log.getActionType() != null) {
                        switch (log.getActionType()) {
                            case CLEAR_TABLE  -> c.setBackground(new Color(255, 205, 210));
                            case REMOVE_ITEM  -> c.setBackground(new Color(255, 224, 178));
                            case DECREASE_ITEM-> c.setBackground(new Color(255, 249, 196));
                            case CANCEL_ORDER -> c.setBackground(new Color(239, 154, 154));
                        }
                    } else {
                        c.setBackground(Color.WHITE);
                    }
                }
                return c;
            }
        });

        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createTitledBorder("İade & İptal Kayıtları"));
        return sp;
    }

    private JComponent buildFooter() {
        summaryLabel.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 13f));
        return summaryLabel;
    }

    // ---- veri yükleme ----

    private void reload() {
        DateRangeOption opt = (DateRangeOption) rangeCombo.getSelectedItem();
        List<RefundLog> data;
        if (opt == null || opt == DateRangeOption.ALL) {
            data = appState.getAllRefundLogs();
        } else {
            LocalDate to = LocalDate.now();
            LocalDate from = to.minusDays(opt.days - 1);
            data = appState.getRefundLogsByDateRange(from, to);
        }
        tableModel.setData(data);
        applyFilters();
    }

    private void applyFilters() {
        String actionFilter = (String) actionCombo.getSelectedItem();
        String userQuery = userFilter.getText() == null ? "" : userFilter.getText().trim().toLowerCase();
        tableModel.applyFilter(actionFilter, userQuery);
        updateSummary();
    }

    private void updateSummary() {
        int count = tableModel.getRowCount();
        BigDecimal total = MoneyUtil.sumAmounts(tableModel.filtered, RefundLog::getAmount);
        summaryLabel.setText("Toplam: " + count + " kayıt   |   Toplam iade tutarı: "
                + MONEY_FMT.format(total));
    }

    /** Tarih aralığı seçenekleri. */
    private enum DateRangeOption {
        TODAY("Bugün", 1),
        LAST_7("Son 7 gün", 7),
        LAST_30("Son 30 gün", 30),
        ALL("Tümü", 0);

        final String label;
        final int days;
        DateRangeOption(String label, int days) { this.label = label; this.days = days; }
        @Override public String toString() { return label; }
    }

    /** Tablo modeli — filtreli veri sağlar. */
    private static class LogTableModel extends AbstractTableModel {
        private static final String[] COLS = {
                "Tarih", "Kullanıcı", "İşlem", "Masa", "Ürün", "Adet", "Tutar", "Neden"
        };
        private List<RefundLog> all = new ArrayList<>();
        private List<RefundLog> filtered = new ArrayList<>();

        void setData(List<RefundLog> data) {
            this.all = data == null ? new ArrayList<>() : new ArrayList<>(data);
            this.filtered = new ArrayList<>(this.all);
            fireTableDataChanged();
        }

        void applyFilter(String actionFilter, String userQuery) {
            filtered.clear();
            for (RefundLog l : all) {
                if (l == null) continue;
                if (actionFilter != null && !"Tümü".equals(actionFilter)) {
                    if (l.getActionType() == null
                            || !actionFilter.equals(l.getActionType().name())) continue;
                }
                if (userQuery != null && !userQuery.isBlank()) {
                    String name = l.getUserName() == null ? "" : l.getUserName().toLowerCase();
                    if (!name.contains(userQuery)) continue;
                }
                filtered.add(l);
            }
            fireTableDataChanged();
        }

        RefundLog rowAt(int row) {
            if (row < 0 || row >= filtered.size()) return null;
            return filtered.get(row);
        }

        @Override public int getRowCount() { return filtered.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int row, int col) {
            RefundLog l = rowAt(row);
            if (l == null) return "";
            return switch (col) {
                case 0 -> l.getCreatedAt() == null ? "" : l.getCreatedAt().format(TS_FMT);
                case 1 -> l.getUserName() == null ? "(?)" : l.getUserName();
                case 2 -> l.getActionType() == null ? "" : l.getActionType().name();
                case 3 -> l.getTableNo() == null ? "" : "Masa " + l.getTableNo();
                case 4 -> l.getProductName() == null ? "(tümü)" : l.getProductName();
                case 5 -> l.getQuantity() == null ? "" : l.getQuantity();
                case 6 -> l.getAmount() == null ? "" : MONEY_FMT.format(l.getAmount());
                case 7 -> l.getReason() == null ? "" : l.getReason();
                default -> "";
            };
        }
    }
}
