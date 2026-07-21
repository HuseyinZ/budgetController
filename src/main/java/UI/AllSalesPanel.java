package UI;

import DataConnection.Db;
import model.MoneyUtil;
import model.PaymentMethod;
import state.AppState;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Satışlar paneli — SİPARİŞ bazlı görünüm.
 *
 * <p>Her satır = bir tamamlanmış sipariş. Sütunlar:
 * Saat | Salon | Masa | Garson | Kasiyer | Ödeme | Toplam Tutar.
 *
 * <p>Bir satıra çift tıklayınca o siparişin içeriği (kalemler + notlar)
 * detay dialog'unda görünür.
 *
 * <p>Erişim: ADMIN-only (DashboardView'da filtrelenir).
 */
public class AllSalesPanel extends JPanel {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final AppState appState;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JSpinner dateSpinner = new JSpinner(
            new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JLabel summaryLabel = new JLabel(" ");
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("tr", "TR"));
    private final PropertyChangeListener listener = this::handleStateChange;

    private final List<OrderRow> currentRows = new ArrayList<>();

    public AllSalesPanel(AppState appState) {
        this.appState = Objects.requireNonNull(appState, "appState");
        setLayout(new BorderLayout(8, 8));

        tableModel = new DefaultTableModel(new Object[]{
                "Saat", "Salon", "Masa", "Garson", "Kasiyer", "Ödeme", "Toplam Tutar"
        }, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(36);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(60);
        table.getColumnModel().getColumn(3).setPreferredWidth(150);
        table.getColumnModel().getColumn(4).setPreferredWidth(150);
        table.getColumnModel().getColumn(5).setPreferredWidth(100);
        table.getColumnModel().getColumn(6).setPreferredWidth(140);

        // Çift tıkla → detay
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) showSelectedDetails();
            }
        });

        add(buildControls(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(summaryLabel, BorderLayout.SOUTH);

        appState.addPropertyChangeListener(listener);
        refreshTable();
    }

    private JComponent buildControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        panel.add(new JLabel("Tarih"));
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "dd-MM-yyyy"));
        panel.add(dateSpinner);
        JButton refresh = new JButton("Listele");
        refresh.addActionListener(e -> refreshTable());
        panel.add(refresh);
        JButton todayBtn = new JButton("Bugün");
        todayBtn.addActionListener(e -> { dateSpinner.setValue(new Date()); refreshTable(); });
        panel.add(todayBtn);
        JButton detailBtn = new JButton("Detay");
        detailBtn.setToolTipText("Seçili siparişin içeriğini gör");
        detailBtn.addActionListener(e -> showSelectedDetails());
        panel.add(detailBtn);
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD, 14f));
        summaryLabel.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 0));
        panel.add(summaryLabel);
        return panel;
    }

    private void handleStateChange(PropertyChangeEvent event) {
        if (AppState.EVENT_SALES.equals(event.getPropertyName())) {
            SwingUtilities.invokeLater(this::refreshTable);
        }
    }

    // ---- Yükleme ----

    private void refreshTable() {
        LocalDate date = pickDate();
        currentRows.clear();
        currentRows.addAll(loadOrders(date));

        tableModel.setRowCount(0);
        BigDecimal total = MoneyUtil.sumAmounts(currentRows, row -> row.amount);
        for (OrderRow r : currentRows) {
            tableModel.addRow(new Object[]{
                    r.closedAt == null ? "-" : HHMM.format(r.closedAt),
                    r.salonName,
                    r.tableNo == null ? "-" : r.tableNo.toString(),
                    r.waiterName,
                    r.cashierName,
                    r.paymentMethod,
                    currencyFormat.format(r.amount)
            });
        }
        summaryLabel.setText(currentRows.size() + " sipariş — Toplam: " + currencyFormat.format(total));
    }

    private LocalDate pickDate() {
        Date d = (Date) dateSpinner.getValue();
        return Instant.ofEpochMilli(d.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * Bir günün satışlarını payment tablosundan getirir.
     *
     * <p>Payment tablosundan başlamamızın sebebi: o.status sütunu hem string
     * ('COMPLETED') hem numerik (3, 4) olarak DB'lerde farklılık gösterebilir.
     * Her payment kaydı = gerçekten alınmış bir ödeme = satılmış sipariş.
     */
    private List<OrderRow> loadOrders(LocalDate date) {
        // noinspection SqlResolve, SqlNoDataSourceInspection
        final String sql =
                "SELECT p.id AS payment_id, p.order_id, p.amount, p.method, p.paid_at, " +
                "       o.note AS order_note, " +
                "       dt.table_no, " +
                "       w.full_name AS waiter_name, w.username AS waiter_user, " +
                "       c.full_name AS cashier_name, c.username AS cashier_user " +
                "  FROM payments p " +
                "  LEFT JOIN orders o         ON o.id = p.order_id " +
                "  LEFT JOIN dining_tables dt ON dt.id = o.table_id " +
                "  LEFT JOIN users w          ON w.id = o.waiter_id " +
                "  LEFT JOIN users c          ON c.id = p.cashier_id " +
                " WHERE DATE(p.paid_at) = ? " +
                " ORDER BY p.paid_at DESC";
        List<OrderRow> out = new ArrayList<>();
        try (Connection c = Db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, java.sql.Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OrderRow r = new OrderRow();
                    r.orderId = rs.getLong("order_id");
                    Timestamp ts = rs.getTimestamp("paid_at");
                    r.closedAt = ts == null ? null : ts.toLocalDateTime();
                    Object tNo = rs.getObject("table_no");
                    r.tableNo = (tNo instanceof Number n) ? n.intValue() : null;
                    r.salonName = resolveSalon(r.tableNo);
                    r.waiterName = preferFull(rs.getString("waiter_name"), rs.getString("waiter_user"));
                    r.cashierName = preferFull(rs.getString("cashier_name"), rs.getString("cashier_user"));
                    BigDecimal amt = rs.getBigDecimal("amount");
                    r.amount = amt == null ? BigDecimal.ZERO : amt;
                    r.paymentMethod = describeMethod(rs.getString("method"));
                    r.orderNote = rs.getString("order_note");
                    out.add(r);
                }
            }
        } catch (SQLException ex) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Satışlar yüklenemedi: " + ex.getMessage(),
                    "Hata", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
        return out;
    }

    private String preferFull(String full, String user) {
        if (full != null && !full.isBlank()) return full;
        if (user != null && !user.isBlank()) return user;
        return "-";
    }

    private String describeMethod(String code) {
        if (code == null) return "-";
        try {
            return PaymentMethod.valueOf(code).getDisplayName();
        } catch (IllegalArgumentException ex) {
            return code;
        }
    }

    private String resolveSalon(Integer tableNo) {
        if (tableNo == null) return "-";
        for (AppState.AreaDefinition a : appState.getAllAreas()) {
            if (a.getTableNumbers().contains(tableNo)) {
                return a.getBuilding() + " / " + a.getSection();
            }
        }
        return "-";
    }

    // ---- Detay ----

    private void showSelectedDetails() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Detay için bir sipariş seçin", "Bilgi", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (row >= currentRows.size()) return;
        OrderRow r = currentRows.get(row);
        new OrderDetailDialog(SwingUtilities.getWindowAncestor(this), r).setVisible(true);
    }

    // ---- Veri taşıyıcı ----
    private static class OrderRow {
        long orderId;
        LocalDateTime closedAt;
        Integer tableNo;
        String salonName;
        String waiterName;
        String cashierName;
        BigDecimal amount;
        String paymentMethod;
        String orderNote;
    }

    // ---- Detay dialog ----
    private class OrderDetailDialog extends JDialog {
        OrderDetailDialog(Window owner, OrderRow r) {
            super(owner, "Sipariş #" + r.orderId + " — " + r.salonName + " / Masa " + r.tableNo,
                    ModalityType.APPLICATION_MODAL);
            setLayout(new BorderLayout(8, 8));
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);

            JLabel header = new JLabel(
                    "<html><div style='padding:8px;'>"
                  + "<b>Saat:</b> " + (r.closedAt == null ? "-" : r.closedAt.format(HHMM))
                  + "  &nbsp; <b>Garson:</b> " + r.waiterName
                  + "  &nbsp; <b>Kasiyer:</b> " + r.cashierName
                  + "<br/><b>Toplam:</b> " + currencyFormat.format(r.amount)
                  + "  &nbsp; <b>Ödeme:</b> " + r.paymentMethod
                  + (r.orderNote == null || r.orderNote.isBlank() ? ""
                          : "<br/><b>Not:</b> " + r.orderNote)
                  + "</div></html>");
            add(header, BorderLayout.NORTH);

            DefaultTableModel m = new DefaultTableModel(
                    new Object[]{"Ürün", "Adet", "Birim Fiyat", "Toplam", "Not"}, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
            JTable items = new JTable(m);
            items.setRowHeight(30);
            loadItems(r.orderId, m);
            add(new JScrollPane(items), BorderLayout.CENTER);

            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton close = new JButton("Kapat");
            close.addActionListener(e -> dispose());
            south.add(close);
            add(south, BorderLayout.SOUTH);

            setPreferredSize(new Dimension(720, 480));
            pack();
            setLocationRelativeTo(owner);
        }

        private void loadItems(long orderId, DefaultTableModel m) {
            // noinspection SqlResolve, SqlNoDataSourceInspection
            final String sqlFull =
                    "SELECT product_name, quantity, unit_price, line_total, note " +
                    "  FROM order_items WHERE order_id = ? ORDER BY id";
            // noinspection SqlResolve, SqlNoDataSourceInspection
            final String sqlBasic =
                    "SELECT product_name, quantity, unit_price, line_total " +
                    "  FROM order_items WHERE order_id = ? ORDER BY id";
            boolean noteAvailable = true;
            try (Connection c = Db.getConnection();
                 PreparedStatement ps = c.prepareStatement(sqlFull)) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    addItemRows(rs, m, true);
                }
            } catch (SQLException ex) {
                noteAvailable = false;
            }
            if (!noteAvailable) {
                try (Connection c = Db.getConnection();
                     PreparedStatement ps = c.prepareStatement(sqlBasic)) {
                    ps.setLong(1, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        addItemRows(rs, m, false);
                    }
                } catch (SQLException ignored) {
                }
            }
            if (m.getRowCount() == 0) {
                m.addRow(new Object[]{"(kalem yok)", 0, "-", "-", ""});
            }
        }

        private void addItemRows(ResultSet rs, DefaultTableModel m, boolean withNote) throws SQLException {
            while (rs.next()) {
                BigDecimal unitPrice = rs.getBigDecimal("unit_price");
                BigDecimal lineTotal = rs.getBigDecimal("line_total");
                if (lineTotal == null && unitPrice != null) {
                    lineTotal = unitPrice.multiply(BigDecimal.valueOf(rs.getInt("quantity")));
                }
                String note = withNote ? rs.getString("note") : "";
                m.addRow(new Object[]{
                        rs.getString("product_name"),
                        rs.getInt("quantity"),
                        currencyFormat.format(unitPrice == null ? BigDecimal.ZERO : unitPrice),
                        currencyFormat.format(lineTotal == null ? BigDecimal.ZERO : lineTotal),
                        note == null ? "" : note
                });
            }
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        appState.removePropertyChangeListener(listener);
    }
}
