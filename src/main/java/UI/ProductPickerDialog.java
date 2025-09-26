package UI;

import model.Category;
import model.Product;

import service.CategoryService;
import service.ProductService;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class ProductPickerDialog extends JDialog {

    public record Selection(Long productId, int quantity) {}

    private static final int GRID_COLUMNS = 2;
    private static final int MAX_QUANTITY = 20;
    private static final String PLACEHOLDER_RESOURCE = "/images/placeholder.png";
    private static final String PRODUCT_IMAGE_PATTERN = "/images/products/%d.png";

    private final CategoryService categoryService = new CategoryService();
    private final ProductService productService = new ProductService(categoryService);
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("tr", "TR"));
    private final JPanel grid = new JPanel(new GridLayout(0, GRID_COLUMNS, 8, 8));
    private final JLabel messageLabel = new JLabel(" ");
    private final ButtonGroup filterGroup = new ButtonGroup();
    private final JToggleButton allButton = new JToggleButton("Tümü");
    private final List<ProductTile> productTiles = new ArrayList<>();
    private final Map<Long, Integer> selectedQuantities = new HashMap<>();

    private Consumer<Selection> onSelect;
    private Long activeCategoryId;

    public ProductPickerDialog(Window owner, int tableNo) {
        this(owner, tableNo > 0 ? "Masa " + tableNo + " - Ürün Seç" : "Ürün Seç");
    }

    public ProductPickerDialog(Window owner) {
        this(owner, "Ürün Seç");
    }

    private ProductPickerDialog(Window owner, String title) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        setPreferredSize(new Dimension(760, 560));

        add(buildFilterBar(), BorderLayout.NORTH);
        add(buildGridPanel(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        loadProducts(null);
        pack();
        setLocationRelativeTo(owner);
    }

    public void setOnSelect(Consumer<Selection> onSelect) {
        this.onSelect = onSelect;
    }

    private JComponent buildFilterBar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        configureFilterButton(panel, allButton, null);
        populateCategoryButtons(panel);
        allButton.setSelected(true);
        allButton.setPreferredSize(new Dimension(160, 48));
        activeCategoryId = null;
        return panel;
    }

    private void configureFilterButton(JPanel panel, JToggleButton button, Long categoryId) {
        button.addActionListener(e -> {
            if (!Objects.equals(activeCategoryId, categoryId)) {
                activeCategoryId = categoryId;
                clearMessage();
                loadProducts(categoryId);
            }
        });
        filterGroup.add(button);
        panel.add(button);
        button.setPreferredSize(new Dimension(160, 48));
    }

    private void populateCategoryButtons(JPanel panel) {
        List<Category> categories;
        try {
            categories = categoryService.getAllCategories();
        } catch (RuntimeException ex) {
            showMessage("Kategori bilgileri yüklenemedi: " + ex.getMessage(), true);
            return;
        }

        if (categories == null || categories.isEmpty()) {
            return;
        }

        for (Category category : categories) {
            if (category == null || !category.isActive()) {
                continue;
            }
            String name = categoryName(category);
            JToggleButton button = new JToggleButton(name);
            configureFilterButton(panel, button, category.getId());
        }
    }

    private JComponent buildGridPanel() {
        grid.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        grid.setOpaque(false);
        JScrollPane scrollPane = new JScrollPane(grid);
        scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        return scrollPane;
    }

    private JComponent buildFooter() {
        JPanel panel = new JPanel(new BorderLayout());
        messageLabel.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        messageLabel.setForeground(Color.DARK_GRAY);
        panel.add(messageLabel, BorderLayout.CENTER);
        JButton addSelectedButton = new JButton("Ürünleri Ekle");
        addSelectedButton.addActionListener(e -> addSelectedProducts());
        addSelectedButton.setPreferredSize(new Dimension(160,43));
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        leftButtons.add(addSelectedButton);
        panel.add(leftButtons, BorderLayout.WEST);
        JButton closeButton = new JButton("Kapat");
        closeButton.setPreferredSize(new Dimension(160,43));
        closeButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(closeButton);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    private void loadProducts(Long categoryId) {
        List<Product> products;
        try {
            if (categoryId == null) {
                products = productService.getAllProducts();
            } else {
                products = productService.getProductsByCategory(categoryId, 0, 200);
            }
            clearMessage();
        } catch (RuntimeException ex) {
            products = List.of();
            showMessage("Ürünler yüklenemedi: " + ex.getMessage(), true);
        }
        renderProducts(products);
    }

    private String categoryName(Category category) {
        if (category == null) {
            return "Kategori";
        }
        String name = category.getName();
        if (name == null) {
            return "Kategori";
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() ? "Kategori" : trimmed;
    }

    private void renderProducts(List<Product> products) {
        grid.removeAll();
        productTiles.clear();
        int count = 0;
        if (products != null) {
            for (Product product : products) {
                if (product == null || !product.isActive()) {
                    continue;
                }
                int initialQty = selectedQuantities.getOrDefault(product.getId(), 0);
                ProductTile tile = new ProductTile(product, initialQty);
                grid.add(tile);
                productTiles.add(tile);
                count++;
            }
        }
        if (count == 0) {
            grid.add(createEmptyTile());
        } else if (count % GRID_COLUMNS != 0) {
            JPanel filler = new JPanel();
            filler.setOpaque(false);
            grid.add(filler);
        }
        grid.revalidate();
        grid.repaint();
    }

    private JPanel createEmptyTile() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JLabel label = new JLabel("Bu kategoride ürün yok.", SwingConstants.CENTER);
        label.setForeground(Color.DARK_GRAY);
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private void showMessage(String text, boolean error) {
        messageLabel.setForeground(error ? Color.RED.darker() : new Color(0, 128, 0));
        messageLabel.setText(text);
    }

    private void clearMessage() {
        messageLabel.setForeground(Color.DARK_GRAY);
        messageLabel.setText(" ");
    }

    private String formatCurrency(BigDecimal price) {
        if (price == null) {
            price = BigDecimal.ZERO;
        }
        return currencyFormat.format(price);
    }

    private Icon loadProductIcon(Product product) {
        Long productId = product == null ? null : product.getId();
        Image image = null;
        if (productId != null) {
            image = loadImageFromResource(String.format(Locale.ROOT, PRODUCT_IMAGE_PATTERN, productId));
        }
        if (image == null) {
            image = loadImageFromResource(PLACEHOLDER_RESOURCE);
        }
        if (image == null) {
            return UIManager.getIcon("OptionPane.informationIcon");
        }
        Image scaled = image.getScaledInstance(96, 96, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
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

    private String productName(Product product) {
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

    private void adjustSpinnerValue(JSpinner spinner, int delta) {
        Number value = (Number) spinner.getValue();
        int current = value == null ? 0 : value.intValue();
        int updated = Math.max(0, Math.min(MAX_QUANTITY, current + delta));
        spinner.setValue(updated);
    }

    private void addSelectedProducts() {

        clearMessage();
        int added = 0;
        for (ProductTile tile : productTiles) {
            updateSelection(tile.getProductId(), tile.getQuantity());
        }
        for (Map.Entry<Long, Integer> entry : selectedQuantities.entrySet()) {
            Long productId = entry.getKey();
            int quantity = entry.getValue();
            if (productId != null && quantity > 0) {
                if (onSelect != null) {
                    onSelect.accept(new Selection(productId, quantity));
                }
                added++;
            }
        }
        if (added == 0) {
            showMessage("Siparişe eklenecek ürün seçmediniz", true);
            return;
        }
        dispose();
    }

    private class ProductTile extends JPanel {
        private final Long productId;
        private final JSpinner qtySpinner;

        ProductTile(Product product, int initialQuantity) {

            super(new BorderLayout(8, 8));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(220, 220, 220)),
                    BorderFactory.createEmptyBorder(12, 12, 12, 12)));
            setBackground(Color.WHITE);

            productId = product == null ? null : product.getId();

            JLabel iconLabel = new JLabel(loadProductIcon(product));
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            iconLabel.setVerticalAlignment(SwingConstants.CENTER);
            iconLabel.setPreferredSize(new Dimension(110, 110));
            add(iconLabel, BorderLayout.WEST);

            JPanel infoPanel = new JPanel();
            infoPanel.setOpaque(false);
            infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
            JLabel nameLabel = new JLabel(productName(product));
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 14f));
            JLabel priceLabel = new JLabel(formatCurrency(product.getUnitPrice()));
            priceLabel.setForeground(new Color(0, 102, 153));
            infoPanel.add(nameLabel);
            infoPanel.add(Box.createVerticalStrut(6));
            infoPanel.add(priceLabel);
            add(infoPanel, BorderLayout.CENTER);

            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
            controls.setOpaque(false);
            JButton minus = new JButton("-");
            qtySpinner = new JSpinner(new SpinnerNumberModel(0, 0, MAX_QUANTITY, 1));
            Dimension preferred = qtySpinner.getPreferredSize();
            qtySpinner.setPreferredSize(new Dimension(60, preferred.height));
            JButton plus = new JButton("+");

            minus.addActionListener(e -> adjustSpinnerValue(qtySpinner, -1));
            plus.addActionListener(e -> adjustSpinnerValue(qtySpinner, 1));

            if (initialQuantity > 0) {
                qtySpinner.setValue(Math.min(initialQuantity, MAX_QUANTITY));
            }
            qtySpinner.addChangeListener(e -> updateSelection(productId, getQuantity()));

            controls.add(minus);
            controls.add(qtySpinner);
            controls.add(plus);
            add(controls, BorderLayout.SOUTH);
        }

        Long getProductId() {
            return productId;
        }

        int getQuantity() {
            Number number = (Number) qtySpinner.getValue();
            return number == null ? 0 : number.intValue();
        }
    }

    private void updateSelection(Long productId, int quantity) {
        if (productId == null) {
            return;
        }
        if (quantity <= 0) {
            selectedQuantities.remove(productId);
        } else {
            selectedQuantities.put(productId, Math.min(quantity, MAX_QUANTITY));
        }
    }
}
