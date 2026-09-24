package UI;

import model.RestaurantArea;
import model.TableLayoutEntry;
import model.User;
import service.layout.LayoutAutoArrange;
import service.layout.LayoutPlacementRules;
import service.layout.RestaurantLayoutManagementService;
import state.AppState;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ADMIN masa düzeni editörü — "Masa Düzeni" sekmesi.
 *
 * <p>Kurallar:
 * <ul>
 *   <li>Editörü açmak DB'ye YAZMAZ. Koordinatı olmayan masalar geçici
 *       ızgarayla gösterilir ({@link LayoutAutoArrange}).</li>
 *   <li>Sürükleme ve görsel alan düzenlemeleri bellekte birikir; yalnız
 *       "Kaydet" tek transaction'da yazar
 *       ({@link RestaurantLayoutManagementService#updatePlacements}).</li>
 *   <li>Alan/masa CRUD işlemleri kendi butonlarıyla anında yazılır.</li>
 *   <li>Tüm DB çağrıları {@link SwingWorker} içinde — EDT bloklanmaz.</li>
 *   <li>Başarılı her mutasyondan sonra: {@code AppState.reloadLayout()} →
 *       yönetim görüntüsü yeniden okunur → UI tazelenir. Reload hatası
 *       ayrı bir mesajla bildirilir; DB değişikliği geri alınmış gibi
 *       gösterilmez.</li>
 * </ul>
 */
public class LayoutEditorPanel extends JPanel {

    private final AppState appState;
    private final User currentUser;
    private final RestaurantLayoutManagementService management;

    private final DefaultListModel<RestaurantArea> areaModel = new DefaultListModel<>();
    private final JList<RestaurantArea> areaList = new JList<>(areaModel);
    private final DefaultListModel<TableLayoutEntry> tableModel = new DefaultListModel<>();
    private final JList<TableLayoutEntry> tableList = new JList<>(tableModel);
    private final LayoutCanvas canvas = new LayoutCanvas();

    private final JSpinner widthSpinner = boundedSpinner(LayoutAutoArrange.DEFAULT_WIDTH);
    private final JSpinner heightSpinner = boundedSpinner(LayoutAutoArrange.DEFAULT_HEIGHT);
    private final JSpinner rotationSpinner = new JSpinner(
            new SpinnerNumberModel(0, 0, LayoutPlacementRules.MAX_ROTATION, 5));
    private final JSpinner orderSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
    private final JComboBox<String> shapeCombo = new JComboBox<>(
            LayoutPlacementRules.SUPPORTED_SHAPES.stream().sorted().toArray(String[]::new));
    /** Izgara adımı — anında uygulanır. */
    private final JSpinner gridSpinner = new JSpinner(new SpinnerNumberModel(
            LayoutCanvas.DEFAULT_GRID_STEP, LayoutCanvas.MIN_GRID_STEP,
            LayoutCanvas.MAX_GRID_STEP, 5));
    private final javax.swing.JCheckBox snapCheck = new javax.swing.JCheckBox("Izgaraya yapış");
    private final JLabel statusLabel = new JLabel(" ");
    /** Diğer eylem butonlarıyla aynı görsel ölçüde. */
    private final JButton saveButton = button("Kaydet", null);

    /** Editörün çalışma kopyası — DB'ye yazılana kadar yalnız bellekte. */
    private final List<TableLayoutEntry> workingTables = new ArrayList<>();
    private final Set<Integer> dirtyTables = new LinkedHashSet<>();
    private RestaurantLayoutManagementService.ManagementView view;
    private boolean suppressPropertyEvents;

    public LayoutEditorPanel(AppState appState, User currentUser) {
        this(appState, currentUser, new RestaurantLayoutManagementService(
                RestaurantLayoutManagementService.usageCheckFrom(
                        new service.RestaurantTableService(), new service.OrderService())));
    }

    LayoutEditorPanel(AppState appState, User currentUser,
                      RestaurantLayoutManagementService management) {
        this.appState = appState;
        this.currentUser = currentUser;
        this.management = management;

        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(buildAreaSide(), BorderLayout.WEST);
        add(buildCanvasSide(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        saveButton.addActionListener(e -> onSavePlacements());

        canvas.setSelectionListener(this::onCanvasSelection);
        canvas.setMovedListener(t -> markDirty(t.getTableNo()));

        reloadFromDatabase("Masa düzeni yüklendi.");
    }

    // ==================================================================
    //  Arayüz kurulumu
    // ==================================================================

    private java.awt.Component buildAreaSide() {
        JPanel panel = new JPanel(new BorderLayout(4, 6));
        // "Alanı Pasifleştir" gibi uzun etiketler 2x2 ızgarada kırpılmadan sığsın
        panel.setPreferredSize(new Dimension(380, 100));

        areaList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        areaList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                RestaurantArea a = (RestaurantArea) value;
                String salon = a.getSalon() == null || a.getSalon().isBlank() ? "" : " / " + a.getSalon();
                setText(a.getBuilding() + " / " + a.getFloor() + salon
                        + (a.isActive() ? "" : "  (pasif)"));
                if (!a.isActive() && !selected) {
                    setForeground(Color.GRAY);
                }
                return this;
            }
        });
        areaList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedArea();
            }
        });

        JPanel areaButtons = new JPanel(new GridLayout(2, 2, 8, 8));
        areaButtons.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        areaButtons.add(button("Yeni Alan", this::onCreateArea));
        areaButtons.add(button("Alanı Düzenle", this::onEditArea));
        areaButtons.add(button("Alanı Pasifleştir", this::onDeactivateArea));
        areaButtons.add(button("Alanı Aktifleştir", this::onReactivateArea));

        panel.add(new JLabel("Alanlar (aktif + pasif)"), BorderLayout.NORTH);
        panel.add(new JScrollPane(areaList), BorderLayout.CENTER);
        panel.add(areaButtons, BorderLayout.SOUTH);
        return panel;
    }

    private java.awt.Component buildCanvasSide() {
        JPanel canvasSide = new JPanel(new BorderLayout(4, 4));
        canvasSide.add(buildGridBar(), BorderLayout.NORTH);
        canvasSide.add(new JScrollPane(canvas), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                canvasSide, buildTableSide());
        split.setResizeWeight(0.75);
        return split;
    }

    /** Izgara ayarları — değişiklikler anında tuvale uygulanır. */
    private java.awt.Component buildGridBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));

        snapCheck.setSelected(true);
        snapCheck.setToolTipText("Sürüklerken masalar ızgaraya yapışır "
                + "(Shift basılı tutarak geçici olarak serbest hareket ettirebilirsiniz)");
        snapCheck.addActionListener(e -> {
            canvas.setSnapEnabled(snapCheck.isSelected());
            gridSpinner.setEnabled(snapCheck.isSelected());
            statusLabel.setText(snapCheck.isSelected()
                    ? "Izgaraya yapışma açık (Shift → serbest hareket)."
                    : "Serbest hareket açık.");
        });

        gridSpinner.setToolTipText("Izgara adımı (0-1000 normalize uzayda)");
        gridSpinner.addChangeListener(e -> {
            canvas.setGridStep((Integer) gridSpinner.getValue());
            statusLabel.setText("Izgara adımı: " + canvas.getGridStep());
        });

        bar.add(new JLabel("Izgara adımı"));
        bar.add(gridSpinner);
        bar.add(snapCheck);
        return bar;
    }

    private java.awt.Component buildTableSide() {
        JPanel panel = new JPanel(new BorderLayout(4, 6));
        panel.setPreferredSize(new Dimension(320, 100));

        tableList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tableList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                TableLayoutEntry t = (TableLayoutEntry) value;
                setText("Masa " + t.getTableNo() + (t.isActive() ? "" : "  (pasif)")
                        + (dirtyTables.contains(t.getTableNo()) ? "  *" : ""));
                if (!t.isActive() && !selected) {
                    setForeground(Color.GRAY);
                }
                return this;
            }
        });
        tableList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                TableLayoutEntry t = tableList.getSelectedValue();
                canvas.setSelected(t == null ? null : t.getTableNo());
                loadProperties(t);
            }
        });

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(labeled("Genişlik", widthSpinner));
        form.add(labeled("Yükseklik", heightSpinner));
        form.add(labeled("Şekil", shapeCombo));
        form.add(labeled("Dönüş (°)", rotationSpinner));
        form.add(labeled("Sıra", orderSpinner));
        form.add(Box.createVerticalStrut(6));

        JPanel tableButtons = new JPanel(new GridLayout(0, 1, 8, 8));
        tableButtons.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        tableButtons.add(button("Masa Ekle", this::onAddTable));
        tableButtons.add(button("Masa Pasifleştir", this::onDeactivateTable));
        tableButtons.add(button("Masa Aktifleştir", this::onReactivateTable));

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(form, BorderLayout.NORTH);
        south.add(tableButtons, BorderLayout.SOUTH);

        panel.add(new JLabel("Masalar"), BorderLayout.NORTH);
        panel.add(new JScrollPane(tableList), BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);

        for (JSpinner spinner : List.of(widthSpinner, heightSpinner, rotationSpinner, orderSpinner)) {
            spinner.addChangeListener(e -> applyPropertyChange());
        }
        shapeCombo.addActionListener(e -> applyPropertyChange());
        return panel;
    }

    private java.awt.Component buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(button("Değişiklikleri Geri Al", this::onDiscard));
        right.add(saveButton);
        bar.add(statusLabel, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    /** Eylem butonu yüksekliği — dokunmatik dostu, etiket kırpılmaz. */
    static final int ACTION_BUTTON_HEIGHT = 50;
    /** Etiketin iki yanında bırakılan boşluk (px). */
    static final int ACTION_BUTTON_PADDING = 28;

    /**
     * Editörün tüm eylem butonları aynı görsel ölçüyle üretilir: etiketin
     * gerçek genişliği ölçülür ve yatay boşluk eklenir, böylece metin hiçbir
     * dilde kırpılmaz.
     */
    static JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(java.awt.Font.BOLD, 14f));
        b.setMargin(new java.awt.Insets(6, 14, 6, 14));
        b.setFocusPainted(false);
        int textWidth = b.getFontMetrics(b.getFont()).stringWidth(text);
        Dimension size = new Dimension(textWidth + ACTION_BUTTON_PADDING * 2, ACTION_BUTTON_HEIGHT);
        b.setPreferredSize(size);
        b.setMinimumSize(size);
        if (action != null) {
            b.addActionListener(e -> action.run());
        }
        return b;
    }

    private static JPanel labeled(String label, java.awt.Component field) {
        JPanel row = new JPanel(new BorderLayout(6, 2));
        row.add(new JLabel(label), BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private static JSpinner boundedSpinner(int initial) {
        return new JSpinner(new SpinnerNumberModel(initial, LayoutPlacementRules.MIN_SIZE,
                LayoutPlacementRules.MAX_COORD, 5));
    }

    // ==================================================================
    //  Veri akışı
    // ==================================================================

    /** Yönetim görüntüsünü arka planda okur ve UI'ı tazeler. EDT bloklanmaz. */
    private void reloadFromDatabase(String successMessage) {
        setBusy(true, "Yükleniyor…");
        new SwingWorker<RestaurantLayoutManagementService.ManagementView, Void>() {
            @Override
            protected RestaurantLayoutManagementService.ManagementView doInBackground() {
                return management.loadForManagement(currentUser);
            }

            @Override
            protected void done() {
                try {
                    view = get();
                    Integer keepArea = selectedAreaId();
                    rebuildAreaList(keepArea);
                    dirtyTables.clear();
                    showSelectedArea();
                    setBusy(false, successMessage);
                } catch (Exception ex) {
                    setBusy(false, "Masa düzeni okunamadı.");
                    showError("Masa düzeni okunamadı", ex);
                }
            }
        }.execute();
    }

    private Integer selectedAreaId() {
        RestaurantArea selected = areaList.getSelectedValue();
        return selected == null ? null : selected.getId();
    }

    private void rebuildAreaList(Integer keepAreaId) {
        areaModel.clear();
        int selectIndex = -1;
        for (RestaurantArea a : view.areas()) {
            areaModel.addElement(a);
            if (keepAreaId != null && keepAreaId.equals(a.getId())) {
                selectIndex = areaModel.size() - 1;
            }
        }
        if (selectIndex < 0 && !areaModel.isEmpty()) {
            selectIndex = 0;
        }
        if (selectIndex >= 0) {
            areaList.setSelectedIndex(selectIndex);
        }
    }

    /** Seçili alanın masalarını çalışma kopyasına alır ve tuvale basar. */
    private void showSelectedArea() {
        workingTables.clear();
        tableModel.clear();
        RestaurantArea area = areaList.getSelectedValue();
        if (view == null || area == null || area.getId() == null) {
            canvas.setTables(List.of(), Set.of());
            return;
        }
        for (TableLayoutEntry source : view.tablesOf(area.getId())) {
            workingTables.add(copy(source));
        }
        // Geçici ızgara — yalnız görüntü, DB'ye yazılmaz
        LayoutAutoArrange.arrangeMissing(workingTables);
        for (TableLayoutEntry t : workingTables) {
            tableModel.addElement(t);
        }
        canvas.setTables(workingTables, dirtyTables);
        canvas.setSelected(null);
        loadProperties(null);
    }

    private void onCanvasSelection(int tableNo) {
        for (int i = 0; i < tableModel.size(); i++) {
            if (tableModel.get(i).getTableNo() == tableNo) {
                tableList.setSelectedIndex(i);
                return;
            }
        }
        tableList.clearSelection();
    }

    private void loadProperties(TableLayoutEntry t) {
        suppressPropertyEvents = true;
        try {
            boolean enabled = t != null;
            widthSpinner.setEnabled(enabled);
            heightSpinner.setEnabled(enabled);
            shapeCombo.setEnabled(enabled);
            rotationSpinner.setEnabled(enabled);
            orderSpinner.setEnabled(enabled);
            if (!enabled) {
                return;
            }
            widthSpinner.setValue(t.getWidth() == null ? LayoutAutoArrange.DEFAULT_WIDTH : t.getWidth());
            heightSpinner.setValue(t.getHeight() == null ? LayoutAutoArrange.DEFAULT_HEIGHT : t.getHeight());
            shapeCombo.setSelectedItem(t.getShape() == null ? LayoutPlacementRules.DEFAULT_SHAPE : t.getShape());
            rotationSpinner.setValue(t.getRotationDeg());
            orderSpinner.setValue(t.getDisplayOrder());
        } finally {
            suppressPropertyEvents = false;
        }
    }

    /**
     * Özellik alanlarını uygular.
     *
     * <p>Genişlik/yükseklik ÖNCE öneri olarak hesaplanır ve diğer aktif
     * masalarla çakışma denetlenir; geçersizse değer SON GEÇERLİ haline geri
     * alınır ve durum çubuğunda uyarı gösterilir. Şekil, dönüş ve sıra
     * davranışı değişmedi. Servis tarafı yine nihai otoritedir.
     */
    private void applyPropertyChange() {
        if (suppressPropertyEvents) {
            return;
        }
        TableLayoutEntry t = tableList.getSelectedValue();
        if (t == null) {
            return;
        }
        int proposedWidth = (Integer) widthSpinner.getValue();
        int proposedHeight = (Integer) heightSpinner.getValue();

        if (LayoutPlacementRules.isPlaced(t) && t.isActive()
                && (proposedWidth != t.getWidth() || proposedHeight != t.getHeight())) {
            boolean outOfBounds = t.getPosX() + proposedWidth > LayoutPlacementRules.MAX_COORD
                    || t.getPosY() + proposedHeight > LayoutPlacementRules.MAX_COORD;
            if (outOfBounds
                    || canvas.wouldOverlap(t, t.getPosX(), t.getPosY(), proposedWidth, proposedHeight)) {
                revertSizeSpinners(t);
                statusLabel.setText(outOfBounds
                        ? "Bu ölçü masayı alan sınırının dışına taşırıyor — değişiklik geri alındı."
                        : "Bu ölçü başka bir masayla çakışıyor — değişiklik geri alındı.");
                return;
            }
            t.setWidth(proposedWidth);
            t.setHeight(proposedHeight);
        } else {
            // Yerleştirilmemiş veya pasif masa aktif düzeni etkilemez
            t.setWidth(proposedWidth);
            t.setHeight(proposedHeight);
        }

        t.setShape((String) shapeCombo.getSelectedItem());
        t.setRotationDeg((Integer) rotationSpinner.getValue());
        t.setDisplayOrder((Integer) orderSpinner.getValue());
        markDirty(t.getTableNo());
        canvas.setTables(workingTables, dirtyTables);
    }

    /** Ölçü alanlarını masanın son geçerli değerlerine döndürür. */
    private void revertSizeSpinners(TableLayoutEntry t) {
        suppressPropertyEvents = true;
        try {
            widthSpinner.setValue(t.getWidth() == null ? LayoutAutoArrange.DEFAULT_WIDTH : t.getWidth());
            heightSpinner.setValue(t.getHeight() == null ? LayoutAutoArrange.DEFAULT_HEIGHT : t.getHeight());
        } finally {
            suppressPropertyEvents = false;
        }
    }

    private void markDirty(int tableNo) {
        dirtyTables.add(tableNo);
        tableList.repaint();
        statusLabel.setText("Kaydedilmemiş yerleşim değişikliği: " + dirtyTables.size() + " masa");
    }

    // ==================================================================
    //  Yerleşim kaydı — tek transaction
    // ==================================================================

    private void onSavePlacements() {
        if (dirtyTables.isEmpty()) {
            statusLabel.setText("Kaydedilecek yerleşim değişikliği yok.");
            return;
        }
        List<TableLayoutEntry> changed = workingTables.stream()
                .filter(t -> dirtyTables.contains(t.getTableNo()))
                .map(LayoutEditorPanel::copy)
                .toList();

        // Çakışma kontrolü kaydetmeden ÖNCE — alanın tamamı üzerinden
        var overlap = LayoutPlacementRules.findOverlap(workingTables);
        if (overlap.isPresent()) {
            JOptionPane.showMessageDialog(this,
                    overlap.get() + ".\nKaydetmeden önce masaları ayırın.",
                    "Çakışma", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setBusy(true, "Kaydediliyor…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                management.updatePlacements(currentUser, changed);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    setBusy(false, "Kaydedilemedi.");
                    showError("Yerleşim kaydedilemedi", ex);
                    return;
                }
                afterSuccessfulMutation(changed.size() + " masanın yerleşimi kaydedildi.");
            }
        }.execute();
    }

    private void onDiscard() {
        dirtyTables.clear();
        showSelectedArea();
        statusLabel.setText("Kaydedilmemiş değişiklikler geri alındı.");
    }

    // ==================================================================
    //  Alan / masa CRUD — anında yazar
    // ==================================================================

    private void onCreateArea() {
        JTextField building = new JTextField();
        JTextField floor = new JTextField();
        JTextField salon = new JTextField();
        JTextField numbers = new JTextField();
        JSpinner order = new JSpinner(new SpinnerNumberModel(areaModel.size() + 1, 0, 9999, 1));
        JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
        form.add(new JLabel("Bina"));   form.add(building);
        form.add(new JLabel("Kat"));    form.add(floor);
        form.add(new JLabel("Salon"));  form.add(salon);
        form.add(new JLabel("Sıra"));   form.add(order);
        form.add(new JLabel("Masa no (virgülle)")); form.add(numbers);

        if (JOptionPane.showConfirmDialog(this, form, "Yeni Alan",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        List<Integer> tableNumbers;
        try {
            tableNumbers = parseTableNumbers(numbers.getText());
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        RestaurantArea area = new RestaurantArea();
        area.setBuilding(building.getText().trim());
        area.setFloor(floor.getText().trim());
        area.setSalon(salon.getText().trim());
        area.setDisplayOrder((Integer) order.getValue());

        List<TableLayoutEntry> tables = new ArrayList<>();
        int i = 1;
        for (Integer no : tableNumbers) {
            TableLayoutEntry t = new TableLayoutEntry();
            t.setTableNo(no);
            t.setDisplayOrder(i++);
            t.setShape(LayoutPlacementRules.DEFAULT_SHAPE);
            tables.add(t);
        }
        runMutation("Alan eklendi.", () -> management.createAreaWithTables(currentUser, area, tables));
    }

    private void onEditArea() {
        RestaurantArea selected = areaList.getSelectedValue();
        if (selected == null) {
            return;
        }
        JTextField building = new JTextField(selected.getBuilding());
        JTextField floor = new JTextField(selected.getFloor());
        JTextField salon = new JTextField(selected.getSalon());
        JSpinner order = new JSpinner(new SpinnerNumberModel(selected.getDisplayOrder(), 0, 9999, 1));
        JPanel form = new JPanel(new GridLayout(0, 2, 4, 4));
        form.add(new JLabel("Bina"));  form.add(building);
        form.add(new JLabel("Kat"));   form.add(floor);
        form.add(new JLabel("Salon")); form.add(salon);
        form.add(new JLabel("Sıra"));  form.add(order);

        if (JOptionPane.showConfirmDialog(this, form, "Alanı Düzenle",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        RestaurantArea edited = new RestaurantArea();
        edited.setId(selected.getId());
        edited.setBuilding(building.getText().trim());
        edited.setFloor(floor.getText().trim());
        edited.setSalon(salon.getText().trim());
        edited.setDisplayOrder((Integer) order.getValue());
        runMutation("Alan güncellendi.", () -> management.updateArea(currentUser, edited));
    }

    private void onDeactivateArea() {
        RestaurantArea selected = areaList.getSelectedValue();
        if (selected == null || !confirm("Alan ve tüm masaları pasifleştirilsin mi?")) {
            return;
        }
        runMutation("Alan pasifleştirildi.",
                () -> management.deactivateArea(currentUser, selected.getId()));
    }

    private void onReactivateArea() {
        RestaurantArea selected = areaList.getSelectedValue();
        if (selected == null || view == null) {
            return;
        }
        List<Integer> numbers = view.tablesOf(selected.getId()).stream()
                .map(TableLayoutEntry::getTableNo)
                .toList();
        if (numbers.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Alanda masa yok; önce masa eklenmeli.", "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        runMutation("Alan aktifleştirildi.",
                () -> management.reactivateArea(currentUser, selected.getId(), numbers));
    }

    private void onAddTable() {
        RestaurantArea selected = areaList.getSelectedValue();
        if (selected == null) {
            return;
        }
        String input = JOptionPane.showInputDialog(this, "Yeni masa numarası:", "Masa Ekle",
                JOptionPane.QUESTION_MESSAGE);
        if (input == null || input.isBlank()) {
            return;
        }
        int tableNo;
        try {
            tableNo = Integer.parseInt(input.trim());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Masa numarası sayı olmalı.",
                    "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        TableLayoutEntry t = new TableLayoutEntry();
        t.setTableNo(tableNo);
        t.setDisplayOrder(tableModel.size() + 1);
        t.setShape(LayoutPlacementRules.DEFAULT_SHAPE);
        runMutation("Masa eklendi.", () -> management.addTable(currentUser, selected.getId(), t));
    }

    private void onDeactivateTable() {
        TableLayoutEntry t = tableList.getSelectedValue();
        if (t == null || !confirm("Masa " + t.getTableNo() + " pasifleştirilsin mi?")) {
            return;
        }
        runMutation("Masa pasifleştirildi.",
                () -> management.deactivateTable(currentUser, t.getTableNo()));
    }

    private void onReactivateTable() {
        TableLayoutEntry t = tableList.getSelectedValue();
        if (t == null) {
            return;
        }
        runMutation("Masa aktifleştirildi.",
                () -> management.reactivateTable(currentUser, t.getTableNo()));
    }

    /** CRUD işlemini arka planda çalıştırır; başarıda ortak tazeleme akışına girer. */
    private void runMutation(String successMessage, Runnable action) {
        setBusy(true, "İşleniyor…");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                action.run();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    setBusy(false, "İşlem yapılamadı.");
                    showError("İşlem yapılamadı", ex);
                    return;
                }
                afterSuccessfulMutation(successMessage);
            }
        }.execute();
    }

    /**
     * Başarılı DB mutasyonundan sonra runtime düzeni ve editörü tazeler.
     *
     * <p>Reload başarısız olursa DB değişikliği GERİ ALINMIŞ gibi gösterilmez:
     * kullanıcıya kaydın yapıldığı ama çalışan düzenin eski geçerli görüntüde
     * kaldığı açıkça söylenir.
     */
    private void afterSuccessfulMutation(String successMessage) {
        String message = successMessage;
        try {
            appState.reloadLayout();
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this,
                    "Veritabanı değişikliği KAYDEDİLDİ.\n\n"
                            + "Ancak çalışan masa düzeni yenilenemedi; uygulama önceki geçerli\n"
                            + "düzenle çalışmaya devam ediyor. Düzeni geçerli hale getirip tekrar\n"
                            + "deneyin veya uygulamayı yeniden başlatın.\n"
                            + "(Teknik ayrıntılar logs/errors.log dosyasında)",
                    "Düzen yenilenemedi", JOptionPane.WARNING_MESSAGE);
            message = successMessage + " (çalışan düzen yenilenemedi)";
        }
        reloadFromDatabase(message);
    }

    // ==================================================================

    private void setBusy(boolean busy, String message) {
        saveButton.setEnabled(!busy);
        areaList.setEnabled(!busy);
        tableList.setEnabled(!busy);
        statusLabel.setText(message);
    }

    private boolean confirm(String question) {
        return JOptionPane.showConfirmDialog(this, question, "Onay",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    /** Kural ihlalleri kullanıcıya olduğu gibi, teknik hatalar özet olarak. */
    private void showError(String title, Exception ex) {
        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
        String detail = (cause instanceof IllegalStateException
                || cause instanceof IllegalArgumentException
                || cause instanceof SecurityException)
                ? cause.getMessage()
                : "Beklenmeyen bir hata oluştu.\n(Teknik ayrıntılar logs/errors.log dosyasında)";
        JOptionPane.showMessageDialog(this, detail, title, JOptionPane.WARNING_MESSAGE);
    }

    static List<Integer> parseTableNumbers(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("En az bir masa numarası girin");
        }
        List<Integer> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int value;
            try {
                value = Integer.parseInt(token);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Geçersiz masa numarası: " + token);
            }
            if (value <= 0) {
                throw new IllegalArgumentException("Masa numarası pozitif olmalı: " + value);
            }
            if (!seen.add(value)) {
                throw new IllegalArgumentException("Masa numarası yinelenmiş: " + value);
            }
            out.add(value);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("En az bir masa numarası girin");
        }
        return out;
    }

    static TableLayoutEntry copy(TableLayoutEntry s) {
        TableLayoutEntry t = new TableLayoutEntry();
        t.setTableNo(s.getTableNo());
        t.setAreaId(s.getAreaId());
        t.setPosX(s.getPosX());
        t.setPosY(s.getPosY());
        t.setWidth(s.getWidth());
        t.setHeight(s.getHeight());
        t.setShape(s.getShape());
        t.setRotationDeg(s.getRotationDeg());
        t.setDisplayOrder(s.getDisplayOrder());
        t.setActive(s.isActive());
        return t;
    }
}
