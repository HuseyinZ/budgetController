package UI;

import model.Product;
import model.User;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class ProductPickerDialog extends JDialog {

    private final AppState appState;
    private final User currentUser;
    private final int tableNo;
    private final JPanel gridPanel = new JPanel(new GridLayout(0, 3, 12, 12));
    private final JLabel messageLabel = new JLabel(" ");
    private final PropertyChangeListener listener = this::handleEvent;
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("tr", "TR"));
    private String activeCategory;
    private List<Product> currentProducts = List.of();

    public ProductPickerDialog(Window owner, AppState appState, User currentUser, int tableNo) {
        super(owner, "Ürün Seçici", ModalityType.APPLICATION_MODAL);
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = currentUser;
        this.tableNo = tableNo;

        setLayout(new BorderLayout(8, 8));
        setPreferredSize(new Dimension(720, 520));

        add(buildFilters(), BorderLayout.NORTH);
        add(buildGrid(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        appState.addPropertyChangeListener(listener);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                appState.removePropertyChangeListener(listener);
            }
        });

        reloadProducts();
        pack();
        setLocationRelativeTo(owner);
    }

    private JComponent buildFilters() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        ButtonGroup group = new ButtonGroup();

        JToggleButton allButton = new JToggleButton("Tüm Ürünler");
        allButton.setSelected(true);
        allButton.addActionListener(e -> {
            activeCategory = null;
            reloadProducts();
        });

        JToggleButton foodsButton = new JToggleButton("Yemekler");
        foodsButton.addActionListener(e -> {
            activeCategory = "Yemekler";
            reloadProducts();
        });

        JToggleButton drinksButton = new JToggleButton("İçecekler");
        drinksButton.addActionListener(e -> {
            activeCategory = "İçecekler";
            reloadProducts();
        });

        group.add(allButton);
        group.add(foodsButton);
        group.add(drinksButton);

        panel.add(allButton);
        panel.add(foodsButton);
        panel.add(drinksButton);
        return panel;
    }

    private JComponent buildGrid() {
        JPanel wrapper = new JPanel(new BorderLayout());
        gridPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JScrollPane scrollPane = new JScrollPane(gridPanel);
        wrapper.add(scrollPane, BorderLayout.CENTER);
        return wrapper;
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
            SwingUtilities.invokeLater(this::reloadProducts);
        }
    }

    private void reloadProducts() {
        List<Product> products;
        try {
            if (activeCategory == null || activeCategory.isBlank()) {
                products = appState.getAvailableProducts();
            } else {
                products = appState.getProductsByCategoryName(activeCategory);
            }
        } catch (RuntimeException ex) {
            products = List.of();
            messageLabel.setText("Ürünler yüklenemedi: " + ex.getMessage());
        }
        currentProducts = products;
        renderProducts();
    }

    private void renderProducts() {
        gridPanel.removeAll();
        if (currentProducts.isEmpty()) {
            JLabel empty = new JLabel("Bu filtrede ürün bulunamadı");
            empty.setHorizontalAlignment(SwingConstants.CENTER);
            gridPanel.setLayout(new BorderLayout());
            gridPanel.add(empty, BorderLayout.CENTER);
        } else {
            gridPanel.setLayout(new GridLayout(0, 3, 12, 12));
            for (Product product : currentProducts) {
                gridPanel.add(new ProductPanel(product));
            }
        }
        gridPanel.revalidate();
        gridPanel.repaint();
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
            showMessage(quantity + " x " + safeName(product) + " eklendi", false);
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

    private String formatPrice(Product product) {
        BigDecimal price = product.getUnitPrice();
        if (price == null) {
            price = BigDecimal.ZERO;
        }
        return currencyFormat.format(price);
    }

    private String safeName(Product product) {
        String name = product.getName();
        if (name == null || name.isBlank()) {
            return "Ürün";
        }
        return name.trim();
    }

    private class ProductPanel extends JPanel {
        private final Product product;
        private final JPanel quantityPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        private final JSpinner quantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 50, 1));

        ProductPanel(Product product) {
            super(new BorderLayout(4, 4));
            this.product = product;
            setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
            JButton button = new JButton(buildLabel(product));
            button.setFocusPainted(false);
            button.setBackground(Color.WHITE);
            button.addActionListener(e -> toggleQuantity());
            add(button, BorderLayout.CENTER);

            JButton addButton = new JButton("Ekle");
            addButton.addActionListener(e -> commitSelection());
            quantityPanel.add(new JLabel("Adet"));
            quantitySpinner.setPreferredSize(new Dimension(60, quantitySpinner.getPreferredSize().height));
            quantityPanel.add(quantitySpinner);
            quantityPanel.add(addButton);
            quantityPanel.setVisible(false);
            add(quantityPanel, BorderLayout.SOUTH);

            JComponent editor = quantitySpinner.getEditor();
            if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
                defaultEditor.getTextField().addActionListener(e -> commitSelection());
            }
        }

        private void toggleQuantity() {
            boolean newState = !quantityPanel.isVisible();
            hideAllQuantityPanels();
            quantityPanel.setVisible(newState);
            if (newState) {
                quantitySpinner.requestFocusInWindow();
            }
            revalidate();
            repaint();
        }

        private void commitSelection() {
            int qty = (Integer) quantitySpinner.getValue();
            addProduct(product, qty);
            quantitySpinner.setValue(1);
            quantityPanel.setVisible(false);
        }

        private String buildLabel(Product product) {
            return "<html><div style='text-align:center'><b>" + safeName(product)
                    + "</b><br/>" + formatPrice(product) + "</div></html>";
        }
    }

    private void hideAllQuantityPanels() {
        for (Component component : gridPanel.getComponents()) {
            if (component instanceof ProductPanel panel) {
                panel.quantityPanel.setVisible(false);
            }
        }
    }
}
