package UI;

import model.Category;
import model.Product;
import model.User;
import state.AppState;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class ProductEditDialog extends JDialog {

    private final AppState appState;
    private final User currentUser;
    private final DefaultListModel<Product> productListModel = new DefaultListModel<>();
    private final JList<Product> productList = new JList<>(productListModel);
    private final JTextField searchField = new JTextField(18);
    private final JTextField nameField = new JTextField(20);
    private final JComboBox<CategoryItem> categoryCombo = new JComboBox<>();
    private final JTextField priceField = new JTextField(10);
    private final JSpinner stockSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 1_000_000, 1));
    private final JLabel messageLabel = new JLabel(" ");
    private final PropertyChangeListener listener = this::handleEvent;

    private List<Product> allProducts = List.of();
    private Product editingProduct;
    private Long editingProductId;

    public ProductEditDialog(Window owner, AppState appState, User currentUser) {
        super(owner, "Ürün Yönetimi", ModalityType.APPLICATION_MODAL);
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = currentUser;

        setLayout(new BorderLayout(12, 12));
        setPreferredSize(new Dimension(780, 520));

        add(buildMainPanel(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        appState.addPropertyChangeListener(listener);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                appState.removePropertyChangeListener(listener);
            }
        });

        loadCategories();
        loadProducts();
        pack();
        setLocationRelativeTo(owner);
    }

    private JComponent buildMainPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 2, 12, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(buildListPanel());
        panel.add(buildFormPanel());
        return panel;
    }

    private JComponent buildListPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel searchPanel = new JPanel(new BorderLayout(4, 4));
        searchPanel.add(new JLabel("Ara"), BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);
        panel.add(searchPanel, BorderLayout.NORTH);

        productList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        productList.setCellRenderer(new ProductCellRenderer());
        productList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Product selected = productList.getSelectedValue();
                showProduct(selected);
            }
        });
        panel.add(new JScrollPane(productList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton newButton = new JButton("Yeni");
        newButton.addActionListener(e -> clearForm());
        buttons.add(newButton);
        panel.add(buttons, BorderLayout.SOUTH);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });

        return panel;
    }

    private JComponent buildFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;

        int row = 0;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Ad"), gc);
        gc.gridx = 1;
        panel.add(nameField, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Kategori"), gc);
        gc.gridx = 1;
        panel.add(categoryCombo, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Fiyat"), gc);
        gc.gridx = 1;
        panel.add(priceField, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Stok"), gc);
        gc.gridx = 1;
        panel.add(stockSpinner, gc);

        row++;
        gc.gridx = 1; gc.gridy = row; gc.anchor = GridBagConstraints.EAST;
        JPanel formButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveButton = new JButton("Kaydet");
        saveButton.addActionListener(e -> saveProduct());
        formButtons.add(saveButton);
        panel.add(formButtons, gc);

        return panel;
    }

    private JComponent buildFooter() {
        JPanel panel = new JPanel(new BorderLayout());
        messageLabel.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        messageLabel.setForeground(Color.DARK_GRAY);
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
            SwingUtilities.invokeLater(this::loadProducts);
        }
    }

    private void loadCategories() {
        categoryCombo.removeAllItems();
        categoryCombo.addItem(new CategoryItem(null, "(Kategori yok)"));
        List<Category> categories = appState.getAllCategories();
        for (Category category : categories) {
            if (category != null) {
                categoryCombo.addItem(new CategoryItem(category.getId(), category.getName()));
            }
        }
    }

    private void loadProducts() {
        try {
            allProducts = new ArrayList<>(appState.getAvailableProducts());
        } catch (RuntimeException ex) {
            allProducts = List.of();
            showMessage("Ürünler yüklenemedi: " + ex.getMessage(), true);
        }
        applyFilter();
    }

    private void applyFilter() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        productListModel.clear();
        for (Product product : allProducts) {
            if (product == null) continue;
            String name = safeName(product).toLowerCase(Locale.ROOT);
            if (query.isEmpty() || name.contains(query)) {
                productListModel.addElement(product);
            }
        }
        if (!productListModel.isEmpty()) {
            if (editingProductId != null) {
                for (int i = 0; i < productListModel.size(); i++) {
                    Product candidate = productListModel.getElementAt(i);
                    if (Objects.equals(candidate.getId(), editingProductId)) {
                        productList.setSelectedIndex(i);
                        editingProduct = candidate;
                        return;
                    }
                }
            }
            productList.setSelectedIndex(0);
        } else {
            clearForm();
        }
    }

    private void showProduct(Product product) {
        editingProduct = product;
        editingProductId = product == null ? null : product.getId();
        if (product == null) {
            clearForm();
            return;
        }
        nameField.setText(product.getName());
        priceField.setText(product.getUnitPrice() == null ? "0" : product.getUnitPrice().toPlainString());
        stockSpinner.setValue(product.getStock() == null ? 0 : product.getStock());
        selectCategory(product.getCategoryId());
    }

    private void clearForm() {
        editingProduct = null;
        editingProductId = null;
        nameField.setText("");
        priceField.setText("0");
        stockSpinner.setValue(0);
        categoryCombo.setSelectedIndex(0);
        productList.clearSelection();
        showMessage("Yeni ürün kaydı", false);
    }

    private void selectCategory(Long categoryId) {
        for (int i = 0; i < categoryCombo.getItemCount(); i++) {
            CategoryItem item = categoryCombo.getItemAt(i);
            if (Objects.equals(item.id(), categoryId)) {
                categoryCombo.setSelectedIndex(i);
                return;
            }
        }
        categoryCombo.setSelectedIndex(0);
    }

    private void saveProduct() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            showMessage("Ürün adı gerekli", true);
            return;
        }
        BigDecimal price;
        try {
            price = parsePrice(priceField.getText());
        } catch (NumberFormatException ex) {
            showMessage("Fiyat değeri geçersiz", true);
            return;
        }
        if (price.signum() < 0) {
            showMessage("Fiyat negatif olamaz", true);
            return;
        }
        int stock = (Integer) stockSpinner.getValue();
        if (stock < 0) {
            showMessage("Stok negatif olamaz", true);
            return;
        }
        CategoryItem categoryItem = (CategoryItem) categoryCombo.getSelectedItem();
        Long categoryId = categoryItem == null ? null : categoryItem.id();

        try {
            if (editingProduct == null || editingProduct.getId() == null) {
                Product product = new Product();
                product.setName(name);
                product.setUnitPrice(price);
                product.setVatRate(Product.DEFAULT_VAT);
                product.setStock(stock);
                product.setCategoryId(categoryId);
                Long id = appState.createProduct(product);
                product.setId(id);
                editingProduct = product;
                editingProductId = id;
                showMessage("Ürün oluşturuldu", false);
            } else {
                editingProduct.setName(name);
                editingProduct.setUnitPrice(price);
                editingProduct.setStock(stock);
                editingProduct.setCategoryId(categoryId);
                appState.updateProduct(editingProduct);
                editingProductId = editingProduct.getId();
                showMessage("Ürün güncellendi", false);
            }
            loadProducts();
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            if (message == null || message.isBlank()) {
                message = "Ürün kaydedilemedi";
            }
            showMessage(message, true);
        }
    }

    private BigDecimal parsePrice(String text) {
        if (text == null) {
            return BigDecimal.ZERO;
        }
        String normalized = text.replace("₺", "").replace(" ", "").replace(',', '.');
        if (normalized.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(normalized);
    }

    private void showMessage(String text, boolean error) {
        messageLabel.setForeground(error ? Color.RED.darker() : new Color(0, 128, 0));
        messageLabel.setText(text);
    }

    private String safeName(Product product) {
        String name = product.getName();
        if (name == null || name.isBlank()) {
            return "Ürün";
        }
        return name.trim();
    }

    private static class ProductCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Product product) {
                String label = product.getName();
                if (label == null || label.isBlank()) {
                    label = "Ürün";
                }
                ((JLabel) c).setText(label);
            }
            return c;
        }
    }

    private record CategoryItem(Long id, String name) {
        @Override
        public String toString() {
            return name == null || name.isBlank() ? "(Kategori yok)" : name;
        }
    }
}
