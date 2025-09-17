package UI;

import model.PaymentMethod;
import model.Role;
import model.User;
import state.AppState;
import state.OrderLine;
import state.OrderLogEntry;
import state.TableOrderStatus;
import state.TableSnapshot;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TableOrderDialog extends JDialog {
    private final AppState appState;
    private final User currentUser;
    private final int tableNo;
    private final DefaultTableModel tableModel = new DefaultTableModel(new Object[]{"Ürün", "Adet", "Birim", "Toplam"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(tableModel);
    private final JTextArea logArea = new JTextArea(8, 24);
    private final JLabel totalLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JComboBox<MenuItem> productCombo;
    private final JSpinner quantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 20, 1));
    private final JButton markServedButton = new JButton("Sipariş hazır");
    private final JButton saleButton = new JButton("Satış yap");
    private final PropertyChangeListener listener = this::handleStateChange;
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("tr", "TR"));
    private final boolean waiterRole;

    private static final List<MenuItem> MENU_ITEMS = IntStream.rangeClosed(1, 12)
            .mapToObj(i -> new MenuItem(i + ". ürün", BigDecimal.valueOf(40 + i * 5L)))
            .collect(Collectors.toList());

    public TableOrderDialog(Window owner, AppState appState, TableSnapshot snapshot, User user) {
        super(owner, "Masa " + snapshot.getTableNo(), ModalityType.APPLICATION_MODAL);
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = Objects.requireNonNull(user, "user");
        this.tableNo = snapshot.getTableNo();
        this.productCombo = new JComboBox<>(MENU_ITEMS.toArray(new MenuItem[0]));
        this.waiterRole = user.getRole() == Role.GARSON;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(12, 12));
        setPreferredSize(new Dimension(720, 520));

        add(buildHeader(snapshot), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        updateFromSnapshot(snapshot);
        appState.addPropertyChangeListener(listener);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                appState.removePropertyChangeListener(listener);
            }
        });
        pack();
        setLocationRelativeTo(owner);
    }

    private JComponent buildHeader(TableSnapshot snapshot) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Masa " + snapshot.getTableNo() + " - " + snapshot.getBuilding() + " / " + snapshot.getSection());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        panel.add(title, BorderLayout.WEST);
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        statusPanel.add(statusLabel);
        panel.add(statusPanel, BorderLayout.EAST);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return panel;
    }

    private JComponent buildCenter() {
        JPanel panel = new JPanel(new BorderLayout(12, 12));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(new JLabel("Ürün"));
        controls.add(productCombo);
        controls.add(new JLabel("Adet"));
        controls.add(quantitySpinner);
        JButton addButton = new JButton("Ekle");
        addButton.addActionListener(e -> addSelectedItem());
        controls.add(addButton);
        JButton decreaseButton = new JButton("Azalt");
        decreaseButton.addActionListener(e -> decrementSelected());
        controls.add(decreaseButton);
        JButton removeButton = new JButton("Sil");
        removeButton.addActionListener(e -> removeSelected());
        controls.add(removeButton);
        panel.add(controls, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane tableScroll = new JScrollPane(table);
        panel.add(tableScroll, BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Değişiklik Geçmişi"));
        panel.add(logScroll, BorderLayout.EAST);

        return panel;
    }

    private JComponent buildFooter() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clearButton = new JButton("Siparişi temizle");
        clearButton.addActionListener(e -> clearOrder());
        clearButton.setVisible(!waiterRole);
        panel.add(clearButton);

        markServedButton.addActionListener(e -> markServed());
        markServedButton.setVisible(!waiterRole);
        panel.add(markServedButton);

        saleButton.addActionListener(e -> performSale());
        boolean canSell = currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.KASIYER;
        saleButton.setVisible(canSell);
        panel.add(saleButton);

        totalLabel.setFont(totalLabel.getFont().deriveFont(Font.BOLD));
        panel.add(totalLabel);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        return panel;
    }

    private void handleStateChange(PropertyChangeEvent event) {
        if (!AppState.EVENT_TABLES.equals(event.getPropertyName())) {
            return;
        }
        Object newValue = event.getNewValue();
        if (newValue instanceof Integer tableNumber && tableNumber == tableNo) {
            SwingUtilities.invokeLater(() -> updateFromSnapshot(appState.snapshot(tableNo)));
        }
    }

    private void addSelectedItem() {
        MenuItem item = (MenuItem) productCombo.getSelectedItem();
        int qty = (int) quantitySpinner.getValue();
        if (item == null || qty <= 0) {
            return;
        }
        appState.addItem(tableNo, item.name, item.price, qty, currentUser);
    }

    private void decrementSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Azaltmak için satır seçin", "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String product = (String) tableModel.getValueAt(row, 0);
        appState.decreaseItem(tableNo, product, 1, currentUser);
    }

    private void removeSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Silmek için satır seçin", "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String product = (String) tableModel.getValueAt(row, 0);
        int confirm = JOptionPane.showConfirmDialog(this, product + " ürününü silmek istiyor musunuz?", "Onay", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            appState.removeItem(tableNo, product, currentUser);
        }
    }

    private void clearOrder() {
        int choice = JOptionPane.showConfirmDialog(this, "Tüm siparişler silinsin mi?", "Onay", JOptionPane.YES_NO_OPTION);
        if (choice == JOptionPane.YES_OPTION) {
            appState.clearTable(tableNo, currentUser);
        }
    }

    private void markServed() {
        appState.markServed(tableNo, currentUser);
    }

    private void performSale() {
        TableSnapshot snapshot = appState.snapshot(tableNo);
        if (snapshot.getTotal() == null || snapshot.getTotal().compareTo(BigDecimal.ZERO) <= 0) {
            JOptionPane.showMessageDialog(this, "Satış yapılacak ürün bulunamadı", "Bilgi", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<String> methodCombo = new JComboBox<>(new String[]{"Nakit", "Kredi Kartı", "Havale"});
        int result = JOptionPane.showConfirmDialog(this, methodCombo, "Ödeme yöntemi", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        String selected = (String) methodCombo.getSelectedItem();
        PaymentMethod method = mapPaymentMethod(selected);
        appState.recordSale(tableNo, method, currentUser);
        JOptionPane.showMessageDialog(this, "Satış tamamlandı", "Bilgi", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    private PaymentMethod mapPaymentMethod(String selection) {
        if (selection == null) {
            return PaymentMethod.CASH;
        }
        return switch (selection) {
            case "Kredi Kartı" -> PaymentMethod.CREDIT_CARD;
            case "Havale" -> PaymentMethod.TRANSFER;
            default -> PaymentMethod.CASH;
        };
    }

    private void updateFromSnapshot(TableSnapshot snapshot) {
        tableModel.setRowCount(0);
        for (OrderLine line : snapshot.getLines()) {
            tableModel.addRow(new Object[]{
                    line.getProductName(),
                    line.getQuantity(),
                    currencyFormat.format(line.getUnitPrice()),
                    currencyFormat.format(line.getLineTotal())
            });
        }
        String historyText = snapshot.getHistory().stream()
                .map(this::formatLog)
                .collect(Collectors.joining("\n"));
        logArea.setText(historyText);
        logArea.setCaretPosition(logArea.getDocument().getLength());
        totalLabel.setText("Toplam: " + currencyFormat.format(snapshot.getTotal()));
        updateStatus(snapshot.getStatus());
    }

    private String formatLog(OrderLogEntry entry) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        return entry.getTimestamp().format(formatter) + " - " + entry.getMessage();
    }

    private void updateStatus(TableOrderStatus status) {
        String text;
        Color color;
        if (status == null) {
            text = "Masa durumu belirsiz";
            color = Color.GRAY;
        } else {
            switch (status) {
                case EMPTY -> {
                    text = "Masa boş";
                    color = Color.DARK_GRAY;
                }
                case ORDERED -> {
                    text = "Sipariş alındı";
                    color = new Color(239, 108, 0);
                }
                case SERVED -> {
                    text = "Sipariş servis edildi";
                    color = new Color(46, 125, 50);
                }
                default -> {
                    text = status.name();
                    color = Color.DARK_GRAY;
                }
            }
        }
        statusLabel.setText(text);
        statusLabel.setForeground(color);
        if (waiterRole) {
            markServedButton.setEnabled(false);
            saleButton.setEnabled(false);
        } else {
            markServedButton.setEnabled(status == TableOrderStatus.ORDERED);
            saleButton.setEnabled(status == TableOrderStatus.SERVED || status == TableOrderStatus.ORDERED);
        }
    }

    private record MenuItem(String name, BigDecimal price) {
        @Override
        public String toString() {
            return name + " - " + NumberFormat.getCurrencyInstance(new Locale("tr", "TR")).format(price);
        }
    }
}
