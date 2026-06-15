package UI;

import model.Category;
import model.Product;
import model.User;
import state.AppState;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Top-level "Ürünler" paneli.
 *
 * <p>{@link ProductEditDialog} ile aynı özellikleri sağlar; fakat sekme
 * olarak Dashboard üst menüsüne eklenebilen bir JPanel'dir.
 *
 * <p>Yetki: ADMIN + KASIYER erişebilir (DashboardView'da filtrelenir).
 *
 * <p>Form alanları: ad, kategori, fiyat (porsiyon), stok, birim etiketi
 * (porsiyon/şiş/kg/...), porsiyondaki birim sayısı. Şiş bazlı ürünler için
 * pieces_per_portion > 0 girilir.
 */
public class ProductsPanel extends JPanel {

    private final AppState appState;
    private final User currentUser;
    private final DefaultListModel<Product> productListModel = new DefaultListModel<>();
    private final JList<Product> productList = new JList<>(productListModel);
    private final JTextField searchField = new JTextField(18);
    private final JTextField nameField = new JTextField(20);
    private final JComboBox<CategoryItem> categoryCombo = new JComboBox<>();
    private final JTextField priceField = new JTextField(10);
    private final JSpinner stockSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 1_000_000, 1));
    private final JComboBox<String> unitLabelCombo = new JComboBox<>(new String[]{
            "porsiyon", "şiş", "adet", "kg", "tabak", "kase"
    });
    private final JSpinner piecesPerPortionSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100, 1));
    /** "Tükendi/Stokta" toggle butonu — seçili ürünün aktif durumunu değiştirir. */
    private final JButton statusToggleButton = new JButton("(önce ürün seç)");
    private final JLabel messageLabel = new JLabel(" ");
    private final PropertyChangeListener listener = this::handleEvent;

    private List<Product> allProducts = List.of();
    private Product editingProduct;
    private Long editingProductId;

    public ProductsPanel(AppState appState, User currentUser) {
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = currentUser;

        setLayout(new BorderLayout(12, 12));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildMainPanel(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        appState.addPropertyChangeListener(listener);

        loadCategories();
        loadProducts();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        appState.removePropertyChangeListener(listener);
    }

    // ---- UI ----

    private JComponent buildMainPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 2, 12, 12));
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
        JButton deleteButton = new JButton("Sil");
        deleteButton.addActionListener(e -> deleteSelected());
        buttons.add(deleteButton);
        panel.add(buttons, BorderLayout.SOUTH);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        return panel;
    }

    private JComponent buildFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Ürün Detayları"));
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
        panel.add(new JLabel("Porsiyon Fiyatı (₺)"), gc);
        gc.gridx = 1;
        panel.add(priceField, gc);

        // Stok alanı şimdilik UI'dan gizli (kullanıcı isterse ileride açılır).
        // stockSpinner ürün modeline 0 olarak yazılır (default).

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Birim"), gc);
        gc.gridx = 1;
        unitLabelCombo.setEditable(true);
        unitLabelCombo.setToolTipText("Bir porsiyonun tanımı: 'porsiyon', 'şiş', 'kg', 'adet' vs.");
        panel.add(unitLabelCombo, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Porsiyondaki şiş adeti"), gc);
        gc.gridx = 1;
        JPanel piecesRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        piecesRow.add(piecesPerPortionSpinner);
        JLabel hint = new JLabel("0 → şiş bazlı değil");
        hint.setForeground(Color.GRAY);
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
        piecesRow.add(hint);
        panel.add(piecesRow, gc);
        piecesPerPortionSpinner.setToolTipText(
                "Örn. ciğer 4 şiş, adana 2 şiş. 0 → bu ürün şiş bazlı değil; sadece porsiyon sayısı sorulur.");

        // "Tükendi / Stokta" toggle butonu — seçili ürünün aktif durumunu değiştirir
        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Durum"), gc);
        gc.gridx = 1;
        statusToggleButton.addActionListener(e -> toggleProductStatus());
        statusToggleButton.setFont(statusToggleButton.getFont().deriveFont(Font.BOLD, 16f));
        statusToggleButton.setPreferredSize(new Dimension(420, 52));
        statusToggleButton.setMinimumSize(new Dimension(320, 52));
        statusToggleButton.setFocusPainted(false);
        statusToggleButton.setOpaque(true);
        statusToggleButton.setBorderPainted(false);
        statusToggleButton.setHorizontalAlignment(SwingConstants.CENTER);
        statusToggleButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        panel.add(statusToggleButton, gc);

        row++;
        gc.gridx = 1; gc.gridy = row;
        JLabel statusHint = new JLabel(
                "<html><i>Tükendi → garson menüsünde gri/devre dışı görünür, sipariş edilemez</i></html>");
        statusHint.setForeground(Color.GRAY);
        statusHint.setFont(statusHint.getFont().deriveFont(Font.ITALIC, 11f));
        panel.add(statusHint, gc);

        row++;
        gc.gridx = 1; gc.gridy = row; gc.anchor = GridBagConstraints.EAST;
        JPanel formButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveButton = new JButton("Kaydet");
        saveButton.addActionListener(e -> saveProduct());
        formButtons.add(saveButton);
        panel.add(formButtons, gc);

        return panel;
    }

    /** Form'daki status butonunu seçili ürünün aktiflik durumuna göre günceller. */
    private void refreshStatusButton() {
        boolean enabled = editingProduct != null && editingProduct.getId() != null;
        statusToggleButton.setEnabled(enabled);
        if (!enabled) {
            statusToggleButton.setText("(önce ürün seç)");
            statusToggleButton.setBackground(new Color(220, 220, 220));
            statusToggleButton.setForeground(Color.DARK_GRAY);
            return;
        }
        if (editingProduct.isActive()) {
            statusToggleButton.setText("✓ STOKTA  →  Tükendi yap");
            statusToggleButton.setBackground(new Color(76, 175, 80));   // yeşil
            statusToggleButton.setForeground(Color.WHITE);
        } else {
            statusToggleButton.setText("✕ TÜKENDİ  →  Stokta yap");
            statusToggleButton.setBackground(new Color(229, 57, 53));   // kırmızı
            statusToggleButton.setForeground(Color.WHITE);
        }
    }

    /** Tıklayınca seçili ürünün active durumunu tersine çevirir. */
    private void toggleProductStatus() {
        if (editingProduct == null || editingProduct.getId() == null) {
            showMessage("Önce bir ürün seçin", true);
            return;
        }
        boolean newActive = !editingProduct.isActive();
        try {
            appState.setProductActive(editingProduct.getId(), newActive);
            editingProduct.setActive(newActive);
            refreshStatusButton();
            showMessage(newActive
                    ? "'" + safeName(editingProduct) + "' menüye geri eklendi"
                    : "'" + safeName(editingProduct) + "' tükendi olarak işaretlendi", false);
            loadProducts();
        } catch (RuntimeException ex) {
            showMessage("Durum değiştirilemedi: " + ex.getMessage(), true);
        }
    }

    private JComponent buildFooter() {
        JPanel panel = new JPanel(new BorderLayout());
        messageLabel.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        messageLabel.setForeground(Color.DARK_GRAY);
        panel.add(messageLabel, BorderLayout.CENTER);
        return panel;
    }

    // ---- olay ----

    private void handleEvent(PropertyChangeEvent event) {
        if (AppState.EVENT_PRODUCTS.equals(event.getPropertyName())) {
            SwingUtilities.invokeLater(this::loadProducts);
        }
    }

    // ---- veri yükleme ----

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
            // Admin paneli pasif (tükendi) ürünleri de görmek ister — kapatılmış
            // ürünleri tekrar aktif yapabilmek için listede görsünler
            allProducts = new ArrayList<>(appState.getAllProductsIncludingInactive());
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

    // ---- form işlemleri ----

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
        unitLabelCombo.setSelectedItem(product.getUnitLabel() == null ? "porsiyon" : product.getUnitLabel());
        piecesPerPortionSpinner.setValue(product.getPiecesPerPortion() == null ? 0 : product.getPiecesPerPortion());
        refreshStatusButton();
    }

    private void clearForm() {
        editingProduct = null;
        editingProductId = null;
        nameField.setText("");
        priceField.setText("0");
        stockSpinner.setValue(0);
        categoryCombo.setSelectedIndex(0);
        unitLabelCombo.setSelectedItem("porsiyon");
        piecesPerPortionSpinner.setValue(0);
        productList.clearSelection();
        refreshStatusButton();
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

        Object selectedLabel = unitLabelCombo.getSelectedItem();
        String unitLabel = selectedLabel == null ? null : selectedLabel.toString().trim();
        if (unitLabel != null && unitLabel.isEmpty()) {
            unitLabel = null;
        }
        int piecesPerPortion = (Integer) piecesPerPortionSpinner.getValue();
        Integer piecesValue = piecesPerPortion <= 0 ? null : piecesPerPortion;

        try {
            if (editingProduct == null || editingProduct.getId() == null) {
                Product product = new Product();
                product.setName(name);
                product.setUnitPrice(price);
                product.setVatRate(Product.DEFAULT_VAT);
                product.setStock(stock);
                product.setCategoryId(categoryId);
                product.setUnitLabel(unitLabel);
                product.setPiecesPerPortion(piecesValue);
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
                editingProduct.setUnitLabel(unitLabel);
                editingProduct.setPiecesPerPortion(piecesValue);
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

    private void deleteSelected() {
        Product selected = productList.getSelectedValue();
        if (selected == null || selected.getId() == null) {
            showMessage("Önce silinecek ürünü seçin", true);
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "'" + safeName(selected) + "' silinsin mi?",
                "Onay", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            appState.deleteProduct(selected.getId());
            showMessage("Ürün silindi", false);
            clearForm();
            loadProducts();
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            if (message == null || message.isBlank()) {
                message = "Ürün silinemedi (ilişkili siparişler olabilir)";
            }
            showMessage(message, true);
        }
    }

    // ---- yardımcılar ----

    private BigDecimal parsePrice(String text) {
        if (text == null) return BigDecimal.ZERO;
        String normalized = text.replace("₺", "").replace(" ", "").replace(',', '.');
        if (normalized.isBlank()) return BigDecimal.ZERO;
        return new BigDecimal(normalized);
    }

    private void showMessage(String text, boolean error) {
        messageLabel.setForeground(error ? Color.RED.darker() : new Color(0, 128, 0));
        messageLabel.setText(text);
    }

    private String safeName(Product product) {
        String name = product.getName();
        if (name == null || name.isBlank()) return "Ürün";
        return name.trim();
    }

    private static class ProductCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Product product) {
                String label = product.getName();
                if (label == null || label.isBlank()) label = "Ürün";
                if (product.isPieceBased()) {
                    label += "  ·  " + product.getPiecesPerPortion()
                            + " " + (product.getUnitLabel() == null ? "şiş" : product.getUnitLabel())
                            + "/porsiyon";
                }
                // Pasif ürünler → kırmızı "TÜKENDİ" rozeti
                if (!product.isActive()) {
                    label = "<html><span style='color:#c62828;'>● TÜKENDİ</span>  " + label + "</html>";
                    if (!isSelected) {
                        c.setForeground(new Color(120, 120, 120));
                    }
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
