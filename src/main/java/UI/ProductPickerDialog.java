package UI;

import model.Category;
import model.MoneyUtil;
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

    /**
     * Seçim sonucu.
     * <ul>
     *   <li>{@code quantity}: ürün şiş bazlı DEĞİLSE porsiyon sayısı; şiş
     *       bazlıysa toplam birim (şiş) sayısı olarak kullanılır.</li>
     *   <li>{@code piecesOverride}: şiş bazlı ürünlerde toplam şiş sayısı —
     *       {@code null} → ürün şiş bazlı değil, eski davranış.</li>
     *   <li>{@code note}: (opsiyonel) bu kalem için not / özelleştirme.
     *       Örn. "Soğansız, az pişmiş".</li>
     * </ul>
     */
    public record Selection(Long productId, int quantity, Integer piecesOverride, String note) {
        public Selection(Long productId, int quantity) {
            this(productId, quantity, null, null);
        }
        public Selection(Long productId, int quantity, Integer piecesOverride) {
            this(productId, quantity, piecesOverride, null);
        }
    }

    private static final int GRID_COLUMNS = 2;
    private static final int MAX_QUANTITY = 20;
    private static final String PLACEHOLDER_RESOURCE = "/images/placeholder.png";
    private static final String PRODUCT_IMAGE_PATTERN = "/images/products/%d.png";

    private final CategoryService categoryService = new CategoryService();
    private final ProductService productService = new ProductService(categoryService);
    private final NumberFormat currencyFormat = MoneyUtil.turkishLiraCurrencyFormat();
    private final JPanel grid = new JPanel(new GridLayout(0, GRID_COLUMNS, 8, 8));
    private final JLabel messageLabel = new JLabel(" ");
    private final ButtonGroup filterGroup = new ButtonGroup();
    private final JToggleButton allButton = new JToggleButton("Tümü");
    private final List<ProductTile> productTiles = new ArrayList<>();
    private final Map<Long, Integer> selectedQuantities = new HashMap<>();
    private final Map<Long, Integer> selectedPieces = new HashMap<>();  // şiş bazlı toplam birim
    private final Map<Long, String>  selectedNotes  = new HashMap<>();  // İçerik dialog ile

    private Consumer<Selection> onSelect;
    private Long activeCategoryId;
    private boolean fullScreen;
    private Rectangle windowedBounds;

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
        setResizable(true);
        Dimension preferredSize = new Dimension(1300, 800);
        setPreferredSize(preferredSize);
        setMinimumSize(preferredSize);

        add(buildFilterBar(), BorderLayout.NORTH);
        add(buildGridPanel(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        loadProducts(null);
        pack();
        setSize(Math.max(getWidth(), preferredSize.width), Math.max(getHeight(), preferredSize.height));
        setLocationRelativeTo(owner);
    }

    public void setOnSelect(Consumer<Selection> onSelect) {
        this.onSelect = onSelect;
    }

    private JComponent buildFilterBar() {
        JPanel container = new JPanel(new BorderLayout());
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        configureFilterButton(panel, allButton, null);
        populateCategoryButtons(panel);
        allButton.setSelected(true);
        allButton.setPreferredSize(new Dimension(160, 48));
        activeCategoryId = null;
        container.add(panel, BorderLayout.CENTER);


        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        container.add(rightPanel, BorderLayout.EAST);
        return container;
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
            // Pasifler dahil tüm ürünler — tükenmiş olanlar tile içinde gri/disabled
            // görünür, garson "lahmacun tükenmiş" bilgisini ekranda direkt görür.
            if (categoryId == null) {
                products = productService.getAllProducts();
            } else {
                products = productService.getProductsByCategory(categoryId, 0, 200);
            }
            // Aktif olanlar önce, pasifler sonda — sırala
            products = products.stream()
                    .filter(p -> p != null)
                    .sorted((a, b) -> {
                        int byActive = Boolean.compare(!a.isActive(), !b.isActive()); // aktif önce
                        if (byActive != 0) return byActive;
                        String an = a.getName() == null ? "" : a.getName();
                        String bn = b.getName() == null ? "" : b.getName();
                        return an.compareToIgnoreCase(bn);
                    })
                    .collect(java.util.stream.Collectors.toList());
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
                if (product == null) {
                    continue;
                }
                // Pasif (tükendi) ürünler de GÖRÜNÜR — sadece tile içinde
                // gri ve devre dışı olur (garson "lahmacun tükenmiş" görsün)
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

    /** Renk-kodlu +/- buton üretir (büyük ve dokunmatik dostu). */
    private static JButton colorButton(String text, Color color) {
        JButton b = new JButton(text);
        b.setPreferredSize(new Dimension(48, 44));
        b.setFont(b.getFont().deriveFont(Font.BOLD, 18f));
        b.setBackground(color);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setBorderPainted(false);
        return b;
    }

    private void adjustSpinnerValue(JSpinner spinner, int delta) {
        Number value = (Number) spinner.getValue();
        int current = value == null ? 0 : value.intValue();
        // Spinner'ın kendi modelinin min/max'ine saygı duy
        SpinnerNumberModel model = (spinner.getModel() instanceof SpinnerNumberModel)
                ? (SpinnerNumberModel) spinner.getModel() : null;
        int min = (model != null && model.getMinimum() instanceof Number)
                ? ((Number) model.getMinimum()).intValue() : 0;
        int max = (model != null && model.getMaximum() instanceof Number)
                ? ((Number) model.getMaximum()).intValue() : MAX_QUANTITY;
        int updated = Math.max(min, Math.min(max, current + delta));
        spinner.setValue(updated);
    }

    private void addSelectedProducts() {

        clearMessage();
        int added = 0;
        for (ProductTile tile : productTiles) {
            tile.flushSelection();
        }
        for (Map.Entry<Long, Integer> entry : selectedQuantities.entrySet()) {
            Long productId = entry.getKey();
            int quantity = entry.getValue();
            if (productId == null || quantity <= 0) continue;
            if (onSelect != null) {
                Integer pieces = selectedPieces.get(productId);
                String note   = selectedNotes.get(productId);
                onSelect.accept(new Selection(productId, quantity, pieces, note));
            }
            added++;
        }
        if (added == 0) {
            showMessage("Siparişe eklenecek ürün seçmediniz", true);
            return;
        }
        dispose();
    }

    private class ProductTile extends JPanel {
        private final Long productId;
        private final Integer piecesPerPortion;       // null → porsiyon bazlı ürün
        private final String unitLabel;
        private final JSpinner portionSpinner;
        private final JSpinner extraPiecesSpinner;    // sadece şiş bazlı ürünlerde
        private final String productDisplayName;
        private final boolean foodCategory;
        /** İçecek kategorisi — şiş bölümü gizlenir, "porsiyon" yerine "adet" gösterilir. */
        private final boolean drinkCategory;
        private String customNote;                    // "İçerik" dialog ile seçilmiş not

        ProductTile(Product product, int initialQuantity) {

            super(new BorderLayout(8, 8));
            boolean inactive = product != null && !product.isActive();
            // Pasif (tükendi) ürünler için gri arka plan + kırmızı çerçeve
            Color tileBg = inactive ? new Color(240, 240, 240) : Color.WHITE;
            Color tileBorder = inactive ? new Color(200, 80, 80) : new Color(220, 220, 220);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(tileBorder, inactive ? 2 : 1),
                    BorderFactory.createEmptyBorder(12, 12, 12, 12)));
            setBackground(tileBg);

            productId = product == null ? null : product.getId();
            piecesPerPortion = (product == null) ? null : product.getPiecesPerPortion();
            drinkCategory = isDrinkCategory(product);
            unitLabel = (product == null || product.getUnitLabel() == null
                    || product.getUnitLabel().isBlank())
                    ? (isPieceBased() ? "şiş" : (drinkCategory ? "adet" : "porsiyon"))
                    : product.getUnitLabel();
            productDisplayName = productName(product);
            foodCategory = isFoodCategory(product);

            JLabel iconLabel = new JLabel(loadProductIcon(product));
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            iconLabel.setVerticalAlignment(SwingConstants.CENTER);
            iconLabel.setPreferredSize(new Dimension(110, 110));
            if (inactive) {
                // Pasif ikonu gri tonla
                iconLabel.setEnabled(false);
            }
            add(iconLabel, BorderLayout.WEST);

            JPanel infoPanel = new JPanel();
            infoPanel.setOpaque(false);
            infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
            // Pasif ürünlerde başlığa "● TÜKENDİ" rozeti ekle
            String nameHtml = inactive
                    ? "<html><span style='color:#c62828;font-weight:bold;'>● TÜKENDİ &nbsp;</span>"
                            + "<span style='color:#888;'>" + productName(product) + "</span></html>"
                    : productName(product);
            JLabel nameLabel = new JLabel(nameHtml);
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 14f));
            String priceLabelText = formatCurrency(product.getUnitPrice());
            if (isPieceBased()) {
                BigDecimal perPiece = product.getPerPiecePrice();
                priceLabelText += "  (1 " + unitLabel + " = " + formatCurrency(perPiece) + ")";
            }
            JLabel priceLabel = new JLabel(priceLabelText);
            priceLabel.setForeground(inactive ? Color.GRAY : new Color(0, 102, 153));
            infoPanel.add(nameLabel);
            infoPanel.add(Box.createVerticalStrut(6));
            infoPanel.add(priceLabel);
            add(infoPanel, BorderLayout.CENTER);

            // Geniş aralıklı, renk-kodlu kontroller — porsiyon yeşil, şiş mavi
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
            controls.setOpaque(false);

            portionSpinner = new JSpinner(new SpinnerNumberModel(0, 0, MAX_QUANTITY, 1));
            Dimension preferred = portionSpinner.getPreferredSize();
            portionSpinner.setPreferredSize(new Dimension(64, preferred.height));
            extraPiecesSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 50, 1));
            extraPiecesSpinner.setPreferredSize(new Dimension(64, preferred.height));

            // PORSİYON grubu — yeşil
            Color portionColor = new Color(46, 125, 50);
            JPanel portionGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            portionGroup.setOpaque(false);
            portionGroup.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(portionColor, 2, true),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)));
            JButton minus = colorButton("−", portionColor);
            JButton plus  = colorButton("+", portionColor);
            // İçecekler için "Porsiyon" yerine "Adet" göster
            JLabel pLabel = new JLabel(drinkCategory ? "Adet" : "Porsiyon");
            pLabel.setForeground(portionColor);
            pLabel.setFont(pLabel.getFont().deriveFont(Font.BOLD));
            minus.addActionListener(e -> adjustSpinnerValue(portionSpinner, -1));
            plus.addActionListener(e -> adjustSpinnerValue(portionSpinner, 1));
            portionGroup.add(minus);
            portionGroup.add(pLabel);
            portionGroup.add(portionSpinner);
            portionGroup.add(plus);
            controls.add(portionGroup);

            // İçecekler için şiş bölümü asla gösterilmez
            if (isPieceBased() && !drinkCategory) {
                // Aralık (görsel ayırıcı)
                controls.add(Box.createHorizontalStrut(20));

                // ŞİŞ grubu — mavi
                Color pieceColor = new Color(21, 101, 192);
                JPanel pieceGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                pieceGroup.setOpaque(false);
                pieceGroup.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(pieceColor, 2, true),
                        BorderFactory.createEmptyBorder(4, 6, 4, 6)));
                JButton extraMinus = colorButton("−", pieceColor);
                JButton extraPlus  = colorButton("+", pieceColor);
                JLabel sLabel = new JLabel("+ " + unitLabel);
                sLabel.setForeground(pieceColor);
                sLabel.setFont(sLabel.getFont().deriveFont(Font.BOLD));
                extraMinus.addActionListener(e -> adjustSpinnerValue(extraPiecesSpinner, -1));
                extraPlus.addActionListener(e -> adjustSpinnerValue(extraPiecesSpinner, 1));
                pieceGroup.add(extraMinus);
                pieceGroup.add(sLabel);
                pieceGroup.add(extraPiecesSpinner);
                pieceGroup.add(extraPlus);
                controls.add(pieceGroup);
                // başlangıç değeri (eski seçim varsa): pieces ve portion'dan geri hesapla
                Integer storedPieces = selectedPieces.get(productId);
                if (storedPieces != null) {
                    int pp = piecesPerPortion;
                    int portions = storedPieces / pp;
                    int extra = storedPieces - portions * pp;
                    portionSpinner.setValue(Math.min(portions, MAX_QUANTITY));
                    extraPiecesSpinner.setValue(extra);
                } else if (initialQuantity > 0) {
                    portionSpinner.setValue(Math.min(initialQuantity, MAX_QUANTITY));
                }
            } else {
                if (initialQuantity > 0) {
                    portionSpinner.setValue(Math.min(initialQuantity, MAX_QUANTITY));
                }
            }

            // değişiklikte selection map'i güncelle
            javax.swing.event.ChangeListener cl = e -> flushSelection();
            portionSpinner.addChangeListener(cl);
            extraPiecesSpinner.addChangeListener(cl);

            add(controls, BorderLayout.SOUTH);

            // YEMEK kategorisinde "İçerik" butonu — soğansız/tuzsuz vs. seçimi
            if (foodCategory && !inactive) {
                JButton contentBtn = new JButton("İçerik");
                contentBtn.setToolTipText("Bu ürüne içerik tercihleri ekle (soğansız, tuzsuz...)");
                contentBtn.setForeground(new Color(120, 60, 0));
                contentBtn.addActionListener(e -> openContentDialog());
                JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
                topBar.setOpaque(false);
                topBar.add(contentBtn);
                add(topBar, BorderLayout.NORTH);
            }

            // Pasif ürünler için tüm kontrolleri devre dışı bırak —
            // garson görebilir ama sipariş ekleyemez
            if (inactive) {
                disableControls(controls);
                portionSpinner.setEnabled(false);
                extraPiecesSpinner.setEnabled(false);
                setToolTipText("Bu ürün şu anda TÜKENDİ — admin yeniden stokta yapana kadar sipariş edilemez");
            }
        }

        /** Bir kontrol panelindeki tüm bileşenleri (recursive) devre dışı bırakır. */
        private void disableControls(Container container) {
            for (Component c : container.getComponents()) {
                c.setEnabled(false);
                if (c instanceof Container) {
                    disableControls((Container) c);
                }
            }
        }

        private void openContentDialog() {
            ProductNoteDialog dlg = new ProductNoteDialog(
                    SwingUtilities.getWindowAncestor(ProductPickerDialog.this),
                    productDisplayName, customNote);
            String picked = dlg.pickNote();
            if (picked == null) return;
            customNote = picked.isBlank() ? null : picked;
            // Sipariş yokken seçim mevcut değilse minimum 1 porsiyon olsun (kullanıcının
            // not eklemesinin sonucu boş kalmasın diye).
            if (getQuantityForOrder() == 0) {
                portionSpinner.setValue(1);
            }
            flushSelection();
        }

        public String getNote() { return customNote; }

        boolean isPieceBased() {
            // İçecek ise asla şiş bazlı sayma — sadece adet
            if (drinkCategory) return false;
            if (piecesPerPortion == null || piecesPerPortion <= 0) return false;
            // unitLabel "adet/kase/tabak/kg" gibi non-şiş ise yine şiş yok
            String ul = unitLabel == null ? "" : unitLabel.toLowerCase(new java.util.Locale("tr","TR"));
            if (ul.equals("adet") || ul.equals("kase") || ul.equals("tabak")
                    || ul.equals("kg") || ul.equals("lt") || ul.equals("litre")
                    || ul.equals("porsiyon")) {
                return false;
            }
            return true;
        }

        Long getProductId() { return productId; }

        /** Toplam birim sayısını döndürür (şiş bazlı için pieces, porsiyon/adet bazlı için adet). */
        int getQuantityForOrder() {
            int portions = ((Number) portionSpinner.getValue()).intValue();
            if (!isPieceBased()) return portions;
            int extra = ((Number) extraPiecesSpinner.getValue()).intValue();
            return portions * piecesPerPortion + extra;
        }

        Integer getPiecesIfApplicable() {
            return isPieceBased() ? getQuantityForOrder() : null;
        }

        /** Bu kartın seçimini global map'lere yansıtır. */
        void flushSelection() {
            int qty = getQuantityForOrder();
            updateSelection(productId, qty);
            if (isPieceBased() && qty > 0) {
                selectedPieces.put(productId, qty);
            } else {
                selectedPieces.remove(productId);
            }
            // Not da global haritaya yansısın
            if (qty > 0 && customNote != null && !customNote.isBlank()) {
                selectedNotes.put(productId, customNote);
            } else {
                selectedNotes.remove(productId);
            }
        }
    }

    /**
     * Ürünün kategorisini "yemek" kabul ediyor muyuz?
     * <p>Heuristik: kategori adı "yemek", "ana yemek", "kebap", "ızgara", "pide",
     * "lahmacun", "ciğer", "köfte", "tavuk" gibi kelimeler içeriyorsa.
     * <p>İleride bir flag/sütun eklenebilir.
     */
    /**
     * Ürünün "içecek" kategorisinde olup olmadığını tespit eder.
     * İçecek ürünleri için porsiyon/şiş yerine sadece "adet" sorulur.
     *
     * NOT: Locale("tr","TR") ZORUNLU — yoksa Türkçe "İçecek" → "i̇çecek"
     * (combining dot ile) bozulur ve "içecek" keyword'ünü bulamayız.
     */
    private boolean isDrinkCategory(Product product) {
        if (product == null || product.getCategoryId() == null) return false;
        try {
            Category cat = categoryService.getCategoryById(product.getCategoryId());
            if (cat == null || cat.getName() == null) return false;
            String raw = cat.getName();
            // Türkçe locale ile düzgün lowercase
            String l = raw.toLowerCase(new java.util.Locale("tr", "TR"));
            // ASCII versiyonunu da hesapla (çş yazımı farklı olabilir)
            String asciiL = l
                    .replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i')
                    .replace('ö', 'o').replace('ş', 's').replace('ü', 'u');
            String[] keywords = {
                    "içecek", "icecek", "içki", "icki",
                    "su", "ayran", "kola", "çay", "cay",
                    "fanta", "sprite", "kahve", "gazoz",
                    "meşrubat", "mesrubat", "soda", "limonata",
                    "şalgam", "salgam", "şıra", "sira",
                    "bar", "meşrub", "mesrub", "drink", "beverage"
            };
            for (String kw : keywords) {
                if (l.contains(kw) || asciiL.contains(kw)) return true;
            }
            return false;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean isFoodCategory(Product product) {
        if (product == null || product.getCategoryId() == null) return false;
        try {
            Category cat = categoryService.getCategoryById(product.getCategoryId());
            if (cat == null) return false;
            String name = cat.getName();
            if (name == null) return false;
            String l = name.toLowerCase(java.util.Locale.ROOT);
            String[] keywords = {
                    "yemek", "ana yemek", "kebap", "ızgara", "izgara",
                    "pide", "lahmacun", "ciğer", "ciger", "köfte", "kofte",
                    "tavuk", "dürüm", "durum", "döner", "doner", "sıcak"
            };
            for (String kw : keywords) {
                if (l.contains(kw)) return true;
            }
            return false;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void updateSelection(Long productId, int quantity) {
        if (productId == null) {
            return;
        }
        if (quantity <= 0) {
            selectedQuantities.remove(productId);
            selectedPieces.remove(productId);
        } else {
            selectedQuantities.put(productId, Math.min(quantity, MAX_QUANTITY * 10));
        }
    }
}
