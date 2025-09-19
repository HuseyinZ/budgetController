package UI;

import model.Product;
import model.User;
import state.AppState;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class ProductPickerDialog extends JDialog {

    private enum CategoryFilter { ALL, FOODS, DRINKS }

    private final AppState appState;
    private final User currentUser;
    private final int tableNo;
    private final JPanel listPanel = new JPanel();
    private final JLabel messageLabel = new JLabel(" ");
    private final PropertyChangeListener listener = this::handleEvent;
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("tr", "TR"));
    private CategoryFilter activeFilter = CategoryFilter.ALL;
    private final JToggleButton allButton = new JToggleButton("Tümü");
    private final JToggleButton foodsButton = new JToggleButton("Yemekler");
    private final JToggleButton drinksButton = new JToggleButton("İçecekler");

    public ProductPickerDialog(Window owner, AppState appState, int tableNo, User currentUser) {
        super(owner, "Masa " + tableNo + " - Ürün Seç", ModalityType.APPLICATION_MODAL);
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = currentUser;
        this.tableNo = tableNo;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        setPreferredSize(new Dimension(720, 560));

        add(buildFilters(), BorderLayout.NORTH);
        add(buildListPanel(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        appState.addPropertyChangeListener(listener);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                appState.removePropertyChangeListener(listener);
            }
        });

        clearMessage();
        loadActiveProducts();
        pack();
        setLocationRelativeTo(owner);
    }

    private JComponent buildFilters() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        ButtonGroup group = new ButtonGroup();
        configureFilterButton(allButton, CategoryFilter.ALL, group, panel);
        configureFilterButton(foodsButton, CategoryFilter.FOODS, group, panel);
        configureFilterButton(drinksButton, CategoryFilter.DRINKS, group, panel);
        allButton.setSelected(true);
        return panel;
    }

    private void configureFilterButton(JToggleButton button, CategoryFilter filter, ButtonGroup group, JPanel panel) {
        button.addActionListener(e -> {
            if (activeFilter != filter) {
                activeFilter = filter;
                clearMessage();
                loadActiveProducts();
            }
        });
        group.add(button);
        panel.add(button);
    }

    private JComponent buildListPanel() {
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane scrollPane = new JScrollPane(listPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        return scrollPane;
    }

    private JComponent buildFooter() {
        JPanel panel = new JPanel(new BorderLayout());
        messageLabel.setForeground(Color.DARK_GRAY);
        messageLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        panel.add(messageLabel, BorderLayout.CENTER);
        JButton closeButton = new JButton("Kapat");
        closeButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(closeButton);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    private void handleEvent(PropertyChangeEvent event) {
        if (AppState.EVENT_PRODUCTS.equals(event.getPropertyName())) {
            SwingUtilities.invokeLater(this::loadActiveProducts);
        }
    }

    private void loadActiveProducts() {
        List<Product> products;
        try {
            products = switch (activeFilter) {
                case FOODS -> appState.getProductsByCategoryName("Yemekler");
                case DRINKS -> appState.getProductsByCategoryName("İçecekler");
                default -> appState.getAvailableProducts();
            };
        } catch (RuntimeException ex) {
            products = List.of();
            showMessage("Ürünler yüklenemedi: " + ex.getMessage(), true);
        }
        reloadProducts(products);
    }

    private void reloadProducts(List<Product> data) {
        listPanel.removeAll();
        if (data.isEmpty()) {
            JLabel empty = new JLabel("Bu filtrede ürün bulunamadı");
            empty.setHorizontalAlignment(SwingConstants.CENTER);
            empty.setAlignmentX(Component.CENTER_ALIGNMENT);
            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setOpaque(false);
            wrapper.add(empty, BorderLayout.CENTER);
            wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));
            listPanel.add(wrapper);
        } else {
            for (int i = 0; i < data.size(); i++) {
                ProductRowPanel row = new ProductRowPanel(data.get(i));
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                listPanel.add(row);
                if (i < data.size() - 1) {
                    listPanel.add(Box.createVerticalStrut(8));
                }
            }
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    private void addProduct(Product product, int quantity) {
        if (product == null || product.getId() == null) {
            showMessage("Ürün bilgisi eksik", true);
            return;
        }
        if (quantity <= 0) {
            showMessage("Adet en az 1 olmalı", true);
            return;
        }
        try {
            appState.addItem(tableNo, product.getId(), quantity, currentUser);
            showMessage(quantity + " x " + productLabel(product) + " eklendi", false);
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            if (message == null || message.isBlank()) {
                message = "Ürün eklenemedi";
            }
            showMessage(message, true);
        }
    }

    private void showMessage(String text, boolean error) {
        messageLabel.setForeground(error ? Color.RED.darker() : new Color(0, 128, 0));
        messageLabel.setText(text);
    }

    private void clearMessage() {
        messageLabel.setForeground(Color.DARK_GRAY);
        messageLabel.setText(" ");
    }

    private String tl(BigDecimal amount) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        return currencyFormat.format(amount);
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String productLabel(Product product) {
        if (product == null) {
            return "Ürün";
        }
        String name = product.getName();
        if (name == null) {
            return "Ürün";
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? "Ürün" : trimmed;
    }

    private Icon loadProductIcon(Product product) {
        Image image = loadImageForSku(resolveSku(product));
        if (image == null) {
            image = loadImageFromResource("/images/placeholder.png");
        }
        if (image == null) {
            return UIManager.getIcon("OptionPane.informationIcon");
        }
        Image scaled = image.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    private Image loadImageForSku(String sku) {
        if (sku == null || sku.isBlank()) {
            return null;
        }
        return loadImageFromResource("/images/" + sku.trim() + ".png");
    }

    private Image loadImageFromResource(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            java.net.URL resource = getClass().getResource(path);
            if (resource == null) {
                return null;
            }
            return ImageIO.read(resource);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String resolveSku(Product product) {
        if (product == null) {
            return null;
        }
        try {
            Method method = product.getClass().getMethod("getSku");
            Object value = method.invoke(product);
            if (value instanceof String sku && !sku.isBlank()) {
                return sku.trim();
            }
        } catch (ReflectiveOperationException ignore) {
            // sku alanı olmayan modellerde placeholder kullanılacak
        }
        return null;
    }

    private class ProductRowPanel extends JPanel {
        ProductRowPanel(Product product) {
            super(new BorderLayout(8, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 84));

            JLabel pic = new JLabel(loadProductIcon(product));
            pic.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
            add(pic, BorderLayout.WEST);

            JLabel name = new JLabel("<html><b>" + safe(productLabel(product)) + "</b><br/>" + tl(product.getUnitPrice()) + "</html>");
            add(name, BorderLayout.CENTER);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
            right.setOpaque(false);
            JButton minus = new JButton("-");
            JSpinner qty = new JSpinner(new SpinnerNumberModel(1, 1, 99, 1));
            JButton plus = new JButton("+");
            JButton addButton = new JButton("Ekle");

            minus.addActionListener(e -> {
                int current = ((Number) qty.getValue()).intValue();
                qty.setValue(Math.max(1, current - 1));
            });
            plus.addActionListener(e -> {
                int current = ((Number) qty.getValue()).intValue();
                qty.setValue(Math.min(99, current + 1));
            });
            addButton.addActionListener(e -> {
                int quantity = ((Number) qty.getValue()).intValue();
                addProduct(product, quantity);
            });

            Dimension spinnerSize = qty.getPreferredSize();
            qty.setPreferredSize(new Dimension(48, spinnerSize.height));

            right.add(minus);
            right.add(qty);
            right.add(plus);
            right.add(addButton);
            add(right, BorderLayout.EAST);
        }
    }
}
