package UI;

import state.AppState;
import state.TableOrderStatus;
import state.TableSnapshot;

import model.Role;
import model.User;

import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Masa düzenini Bina → Kat → Salon → Masa hiyerarşisiyle gösterir.
 *
 * <p><b>Görünüm — yan panel + içerik:</b>
 * <ul>
 *   <li><b>Sol panel:</b> Tüm hiyerarşi dikey liste olarak görünür.
 *       Bina başlığı sabit; her kat başlık + altındaki salon butonları sıralı.
 *       Salon butonuna tıklayınca o salon "aktif" olur ve sağ panel güncellenir.</li>
 *   <li><b>Sağ panel:</b> Üstte breadcrumb (Bina / Kat / Salon), altta seçili
 *       salonun masa grid'i.</li>
 * </ul>
 *
 * <p>İlk açılışta her binanın ilk salonu otomatik seçilir.
 */
public class RestaurantTablesPanel extends JPanel implements Scrollable {

    /** Aktif salon butonu vurgulama rengi (altın sarısı). */
    private static final Color SELECTED_SALON_BG = new Color(35, 64, 46);
    private static final Color SELECTED_SALON_FG = new Color(40, 20, 0);
    /** Bina başlık rengi. */
    private static final Color BUILDING_COLOR = new Color(64, 1, 1);
    /** Kat başlık rengi. */
    private static final Color FLOOR_COLOR = new Color(110, 50, 50);

    /**
     * Salon butonları için pastel renk paleti — index'e göre döner.
     * Aynı katın salonları farklı renklerle gösterilir.
     */
    private static final Color[] SALON_PALETTE = new Color[] {
            new Color(200, 230, 201),   // açık mavi
            new Color(200, 230, 201),   // açık yeşil
            new Color(200, 230, 201),   // açık turuncu
            new Color(200, 230, 201),   // açık mor
            new Color(200, 230, 201),   // açık pembe
            new Color(200, 230, 201),   // açık sarı
    };

    /** Pastel rengi koyulaştırarak buton kenarlığı için kullanır. */
    private static Color darker(Color c) {
        return new Color(
                Math.max(0, (int) (c.getRed()   * 0.65)),
                Math.max(0, (int) (c.getGreen() * 0.65)),
                Math.max(0, (int) (c.getBlue()  * 0.65)));
    }

    /** Salon buton sayacı — paletten renk seçmek için. */
    private int salonColorIndex = 0;

    private final AppState appState;
    private final User currentUser;
    private final Map<Integer, JButton> tableButtons = new HashMap<>();
    private final Map<String, JButton> salonButtons = new HashMap<>();
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("tr", "TR"));
    private final PropertyChangeListener listener = this::handleStateChange;

    /** Sağ panel breadcrumb etiketi. */
    private JLabel breadcrumbLabel;
    /** Sağ panel scroll wrapper — setViewportView() ile içerik değiştirilir. */
    private JScrollPane rightScroll;
    /** Seçili salon anahtarı — "Bina||Kat||Salon" */
    private String currentSalonKey;
    /** Seçili salonun area tanımı — refresh için. */
    private AppState.AreaDefinition currentArea;
    /** Sayım etiketi (sağ panel üst). */
    private JLabel countsLabel;

    /** Masa durum filtresi seçenekleri. */
    private enum StatusFilter { ALL, EMPTY, OCCUPIED }
    private StatusFilter activeFilter = StatusFilter.ALL;
    private final java.util.Map<StatusFilter, JToggleButton> filterButtons = new java.util.EnumMap<>(StatusFilter.class);

    public RestaurantTablesPanel(AppState appState, User currentUser) {
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        buildLayout();
        appState.addPropertyChangeListener(listener);
    }

    // ============================================================
    //   Layout
    // ============================================================

    private void buildLayout() {
        removeAll();
        tableButtons.clear();
        salonButtons.clear();
        currentSalonKey = null;

        // Garson sadece yetkilendirilmiş alanları görür; Admin/Kasiyer tümünü.
        List<AppState.AreaDefinition> accessible = appState.getAccessibleAreas(currentUser);
        if (accessible.isEmpty() && currentUser.getRole() == Role.GARSON) {
            JLabel info = new JLabel(
                    "<html><div style='padding:20px;color:#888;text-align:center;'>"
                  + "<b>Henüz size atanmış kat/salon yok.</b><br/>"
                  + "Bir yöneticiden 'Kullanıcı İşlemleri' panelinden alan yetkisi vermesini isteyin."
                  + "</div></html>");
            add(info, BorderLayout.CENTER);
            revalidate();
            repaint();
            return;
        }

        // Sol panel (sidebar) — tüm hiyerarşi
        salonColorIndex = 0;
        JPanel sidebar = buildSidebar(accessible);
        JScrollPane leftScroll = new JScrollPane(sidebar,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        leftScroll.setPreferredSize(new Dimension(320, 600));
        javax.swing.border.TitledBorder sidebarBorder =
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(BUILDING_COLOR, 2),
                        "Bina / Kat / Salon");
        sidebarBorder.setTitleFont(sidebarBorder.getTitleFont().deriveFont(Font.BOLD, 14f));
        sidebarBorder.setTitleColor(BUILDING_COLOR);
        leftScroll.setBorder(sidebarBorder);
        leftScroll.getVerticalScrollBar().setUnitIncrement(20);
        add(leftScroll, BorderLayout.WEST);

        // Sağ panel — breadcrumb + filtre çubuğu + masa grid'i
        JPanel rightPanel = new JPanel(new BorderLayout(0, 6));

        // Üst: breadcrumb + filter butonları
        JPanel topRow = new JPanel(new BorderLayout(0, 4));
        breadcrumbLabel = new JLabel(" ");
        breadcrumbLabel.setFont(breadcrumbLabel.getFont().deriveFont(Font.BOLD, 16f));
        breadcrumbLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 2, 0, BUILDING_COLOR),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        topRow.add(breadcrumbLabel, BorderLayout.NORTH);
        topRow.add(buildFilterBar(), BorderLayout.CENTER);
        rightPanel.add(topRow, BorderLayout.NORTH);

        // Boş placeholder ile başla — refreshGrid() içeriği setViewportView ile değiştirir
        rightScroll = new JScrollPane(new JPanel(),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        rightScroll.getVerticalScrollBar().setUnitIncrement(32);
        rightScroll.getVerticalScrollBar().setBlockIncrement(120);
        // Mouse wheel scrolling agresif şekilde garantile
        rightScroll.setWheelScrollingEnabled(true);
        rightPanel.add(rightScroll, BorderLayout.CENTER);

        add(rightPanel, BorderLayout.CENTER);

        // İlk salonu otomatik seç
        if (!accessible.isEmpty()) {
            AppState.AreaDefinition first = accessible.get(0);
            selectSalon(first);
        }

        revalidate();
        repaint();
    }

    /** Sol panelin (sidebar) içeriğini kurar — bina → kat → salon dikey liste. */
    private JPanel buildSidebar(List<AppState.AreaDefinition> accessible) {
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        sidebar.setBackground(Color.WHITE);

        // Bina → (Kat → Salon listesi) yapısı kur (insertion-order korunsun)
        Map<String, Map<String, List<AppState.AreaDefinition>>> byBuilding = new LinkedHashMap<>();
        for (AppState.AreaDefinition area : accessible) {
            byBuilding
                    .computeIfAbsent(area.getBuilding(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(area.getSection(), k -> new ArrayList<>())
                    .add(area);
        }

        byBuilding.forEach((building, floors) -> {
            // Bina başlığı — büyük, kalın, alt çizgili
            JLabel buildingLabel = new JLabel(building);
            buildingLabel.setFont(buildingLabel.getFont().deriveFont(Font.BOLD, 20f));
            buildingLabel.setForeground(Color.WHITE);
            buildingLabel.setOpaque(true);
            buildingLabel.setBackground(BUILDING_COLOR);
            buildingLabel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            buildingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            buildingLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            sidebar.add(buildingLabel);
            sidebar.add(Box.createVerticalStrut(6));

            floors.forEach((floorName, salonList) -> {
                // Kat başlığı — kahverengi şerit
                JLabel floorLabel = new JLabel(floorName);
                floorLabel.setFont(floorLabel.getFont().deriveFont(Font.BOLD, 16f));
                floorLabel.setForeground(Color.WHITE);
                floorLabel.setOpaque(true);
                floorLabel.setBackground(FLOOR_COLOR);
                floorLabel.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 12));
                floorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                floorLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
                sidebar.add(floorLabel);
                sidebar.add(Box.createVerticalStrut(4));

                // Salon butonlarını alfabetik sırala
                salonList.sort(Comparator.comparing(
                        a -> a.getSalon() == null ? "" : a.getSalon(),
                        String.CASE_INSENSITIVE_ORDER));

                for (AppState.AreaDefinition salonArea : salonList) {
                    JButton btn = createSalonButton(building, floorName, salonArea);
                    sidebar.add(btn);
                    sidebar.add(Box.createVerticalStrut(4));
                }
                sidebar.add(Box.createVerticalStrut(6));
            });

            sidebar.add(Box.createVerticalStrut(14));
        });

        sidebar.add(Box.createVerticalGlue());
        return sidebar;
    }

    /** Sol panelde tek bir salon butonu üretir — büyük, renkli, dokunmatik dostu. */
    private JButton createSalonButton(String building, String floorName,
                                      AppState.AreaDefinition salonArea) {
        String displayName = salonArea.hasSalon() ? salonArea.getSalon() : "(Tek Salon)";
        // Salon kalem sayısını parantez içinde göster (örn. "1. Salon (5)")
        int tableCount = salonArea.getTableNumbers().size();
        String label = "  " + displayName + "  (" + tableCount + " masa)";

        // Paletten renk seç (her salon farklı renkle gelir)
        Color baseColor = SALON_PALETTE[salonColorIndex % SALON_PALETTE.length];
        salonColorIndex++;
        Color borderColor = darker(baseColor);

        JButton btn = new JButton(label);
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setFont(btn.getFont().deriveFont(Font.BOLD, 17f));
        btn.setBackground(baseColor);
        btn.setForeground(new Color(30, 30, 30));
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 2, true),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)));
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        btn.setPreferredSize(new Dimension(280, 64));
        btn.setMinimumSize(new Dimension(200, 64));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addActionListener(e -> selectSalon(salonArea));

        // Buton kendi başlangıç rengini "client property" olarak saklasın
        // — selectSalon'da seçim kalkınca eski renge dönüşte kullanılır.
        btn.putClientProperty("baseColor", baseColor);
        btn.putClientProperty("borderColor", borderColor);

        String sKey = salonKey(building, floorName, salonArea.getSalon());
        salonButtons.put(sKey, btn);
        return btn;
    }

    // ============================================================
    //   Salon seçimi & sağ panel güncelleme
    // ============================================================

    /** Sağ panel üst kısmındaki masa durum filtresi şeridi. */
    private JComponent buildFilterBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 12, 4, 12));

        ButtonGroup group = new ButtonGroup();
        JToggleButton allBtn = createFilterButton("Tümü", StatusFilter.ALL,
                new Color(198, 40, 40), Color.WHITE);
        JToggleButton emptyBtn = createFilterButton("Boş", StatusFilter.EMPTY,
                new Color(251, 192, 45), new Color(93, 64, 55));
        JToggleButton occBtn = createFilterButton("Dolu", StatusFilter.OCCUPIED,
                new Color(25, 118, 210), Color.WHITE);
        allBtn.setSelected(true);
        group.add(allBtn); group.add(emptyBtn); group.add(occBtn);
        bar.add(allBtn); bar.add(emptyBtn); bar.add(occBtn);

        countsLabel = new JLabel("");
        countsLabel.setFont(countsLabel.getFont().deriveFont(Font.ITALIC, 12f));
        countsLabel.setForeground(new Color(100, 100, 100));
        countsLabel.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        bar.add(countsLabel);
        return bar;
    }

    private JToggleButton createFilterButton(String label, StatusFilter filter,
                                             Color activeBg, Color activeFg) {
        JToggleButton b = new JToggleButton(label);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setPreferredSize(new Dimension(90, 36));
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setBackground(Color.WHITE);
        b.setForeground(Color.DARK_GRAY);
        b.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200), 2, true));
        b.addItemListener(e -> {
            if (b.isSelected()) {
                b.setBackground(activeBg);
                b.setForeground(activeFg);
                b.setBorder(BorderFactory.createLineBorder(activeBg.darker(), 2, true));
                activeFilter = filter;
                refreshGrid();
            } else {
                b.setBackground(Color.WHITE);
                b.setForeground(Color.DARK_GRAY);
                b.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200), 2, true));
            }
        });
        filterButtons.put(filter, b);
        return b;
    }

    /**
     * Aktif filtre/area değişince grid'i yeniden çiz.
     * <p>Mantık:
     * <ul>
     *   <li><b>Tümü</b> filtresi → sadece seçili salonun masaları</li>
     *   <li><b>Boş/Dolu</b> filtresi → seçili salon ÜSTTE + kullanıcının erişebildiği
     *       tüm diğer salonlardaki filtreye uyan masalar ALTTA (her salon için ayrı başlık)</li>
     * </ul>
     */
    private void refreshGrid() {
        if (currentArea == null) return;
        tableButtons.clear();

        JComponent content;
        if (activeFilter == StatusFilter.ALL) {
            content = buildTablesGrid(currentArea);
        } else {
            content = buildGlobalFilteredView();
        }

        // GARANTİ: panel'e mouse wheel listener ekle — JScrollBar'a doğrudan değer yaz.
        // Bu, Swing'in default scroll dispatching'i çalışmazsa devreye girer.
        installWheelScrollGuarantee(content);

        // setViewportView ile direkt değiştir
        rightScroll.setViewportView(content);
        rightScroll.revalidate();
        rightScroll.repaint();

        // Scroll'u en üste al ve final boyutları yeniden hesaplat
        SwingUtilities.invokeLater(() -> {
            rightScroll.getVerticalScrollBar().setValue(0);
            rightScroll.revalidate();
        });
    }

    /**
     * Bir component'e ve TÜM alt component'lerine MouseWheelListener ekler.
     * Listener doğrudan rightScroll'un vertical scroll bar'ını günceller —
     * default JScrollPane dispatching çalışmasa bile kesin scroll garantisi.
     */
    private void installWheelScrollGuarantee(Component c) {
        java.awt.event.MouseWheelListener listener = e -> {
            if (rightScroll == null) return;
            JScrollBar vsb = rightScroll.getVerticalScrollBar();
            // wheel rotation: 1 = aşağı, -1 = yukarı; her tıkta 32px kay
            int delta = e.getWheelRotation() * 32;
            int newValue = Math.max(vsb.getMinimum(),
                    Math.min(vsb.getMaximum() - vsb.getVisibleAmount(),
                             vsb.getValue() + delta));
            vsb.setValue(newValue);
        };
        addWheelListenerRecursive(c, listener);
    }

    private void addWheelListenerRecursive(Component c, java.awt.event.MouseWheelListener l) {
        c.addMouseWheelListener(l);
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                addWheelListenerRecursive(child, l);
            }
        }
    }

    /**
     * Boş/Dolu filtresi seçiliyken — tüm erişilebilir salonlarda filtreye uyan
     * masaları gösterir. Her salon kendi başlığı ile gruplandırılır.
     * <p>JScrollPane içinde Scrollable davranışı için trackViewportWidth=true
     * (yatay scroll'u önle, dikey scroll çalışsın).
     */
    private JComponent buildGlobalFilteredView() {
        ScrollablePanel container = new ScrollablePanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        List<AppState.AreaDefinition> areas = appState.getAccessibleAreas(currentUser);
        // Önce seçili salon görünsün — onu listenin başına al
        List<AppState.AreaDefinition> ordered = new ArrayList<>();
        for (AppState.AreaDefinition a : areas) {
            String key = salonKey(a.getBuilding(), a.getSection(), a.getSalon());
            if (key.equals(currentSalonKey)) {
                ordered.add(0, a);
            } else {
                ordered.add(a);
            }
        }

        int totalShown = 0;
        for (AppState.AreaDefinition area : ordered) {
            // Bu salonda filtreye uyan masa var mı?
            List<Integer> matching = new ArrayList<>();
            for (Integer tableNo : area.getTableNumbers()) {
                TableOrderStatus st = appState.snapshot(tableNo).getStatus();
                boolean isEmpty = (st == null || st == TableOrderStatus.EMPTY);
                if (activeFilter == StatusFilter.EMPTY && !isEmpty) continue;
                if (activeFilter == StatusFilter.OCCUPIED && isEmpty) continue;
                matching.add(tableNo);
            }
            if (matching.isEmpty()) continue;
            Collections.sort(matching);

            // Salon başlığı
            String headerText = area.getBuilding() + " / " + area.getSection();
            if (area.hasSalon()) headerText += " / " + area.getSalon();
            headerText += "  (" + matching.size() + " masa)";

            JLabel header = new JLabel(headerText);
            header.setFont(header.getFont().deriveFont(Font.BOLD, 14f));
            header.setForeground(BUILDING_COLOR);
            header.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0,
                            activeFilter == StatusFilter.EMPTY
                                    ? new Color(251, 192, 45)
                                    : new Color(25, 118, 210)),
                    BorderFactory.createEmptyBorder(10, 8, 6, 8)));
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            container.add(header);

            // Masaları 5 sütunlu grid içinde
            JPanel grid = new JPanel(new GridLayout(0, 5, 8, 8));
            grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 12, 8));
            grid.setAlignmentX(Component.LEFT_ALIGNMENT);
            for (Integer tableNo : matching) {
                grid.add(createTableButton(tableNo));
            }
            container.add(grid);
            totalShown += matching.size();
        }

        // Sayım label'ını da güncelle (filter sayımı global)
        if (countsLabel != null) {
            String label = (activeFilter == StatusFilter.EMPTY ? "Boş" : "Dolu");
            countsLabel.setText("Tüm restoran  •  " + label + ": " + totalShown + " masa");
        }

        if (totalShown == 0) {
            JLabel none = new JLabel("Hiç " +
                    (activeFilter == StatusFilter.EMPTY ? "boş" : "dolu") +
                    " masa yok", SwingConstants.CENTER);
            none.setForeground(Color.GRAY);
            none.setFont(none.getFont().deriveFont(Font.ITALIC, 14f));
            none.setBorder(BorderFactory.createEmptyBorder(40, 0, 40, 0));
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            container.add(none);
        }

        return container;
    }

    /**
     * Verilen salonu aktif yapar:
     * <ul>
     *   <li>Sol paneldeki ilgili butonu vurgular, diğerlerini sıfırlar.</li>
     *   <li>Sağ paneldeki breadcrumb'ı günceller.</li>
     *   <li>Masa grid'ini bu salonun masalarıyla doldurur.</li>
     * </ul>
     */
    private void selectSalon(AppState.AreaDefinition area) {
        currentArea = area;
        currentSalonKey = salonKey(area.getBuilding(), area.getSection(), area.getSalon());

        // Buton vurgulamasını güncelle:
        //   - Seçili buton: altın sarısı + koyu kahve yazı + kalın çerçeve
        //   - Diğerleri: kendi pastel rengine geri döner
        salonButtons.forEach((k, b) -> {
            if (k.equals(currentSalonKey)) {
                b.setBackground(SELECTED_SALON_BG);
                b.setForeground(SELECTED_SALON_FG);
                b.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(darker(SELECTED_SALON_BG), 3, true),
                        BorderFactory.createEmptyBorder(13, 15, 13, 15)));
            } else {
                Color base = (Color) b.getClientProperty("baseColor");
                Color border = (Color) b.getClientProperty("borderColor");
                if (base == null) base = Color.WHITE;
                if (border == null) border = new Color(200, 200, 200);
                b.setBackground(base);
                b.setForeground(new Color(30, 30, 30));
                b.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(border, 2, true),
                        BorderFactory.createEmptyBorder(14, 16, 14, 16)));
            }
        });

        // Breadcrumb
        StringBuilder crumb = new StringBuilder();
        crumb.append(area.getBuilding()).append(" / ").append(area.getSection());
        if (area.hasSalon()) {
            crumb.append(" / ").append(area.getSalon());
        }
        breadcrumbLabel.setText("  " + crumb);

        // Masa grid'i — yeni filter mantığına bırak
        refreshGrid();
    }

    /** Verilen alana ait masaları grid içinde basar — aktif filtreye göre. */
    private JPanel buildTablesGrid(AppState.AreaDefinition area) {
        // Dış wrapper: Scrollable BoxLayout Y_AXIS — ScrollPane'in mouse wheel
        // scroll'unu kesin destekler (tracksViewportWidth=true, height=false).
        ScrollablePanel wrapper = new ScrollablePanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));

        JPanel grid = new JPanel(new GridLayout(0, 5, 8, 8));
        grid.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        List<Integer> tableNumbers = new ArrayList<>(area.getTableNumbers());
        Collections.sort(tableNumbers);

        // Sayım — sayma için her zaman tüm masaları kontrol et
        int total = tableNumbers.size();
        int empty = 0;
        int occupied = 0;
        for (Integer tableNo : tableNumbers) {
            TableOrderStatus st = appState.snapshot(tableNo).getStatus();
            if (st == null || st == TableOrderStatus.EMPTY) empty++;
            else occupied++;
        }
        if (countsLabel != null) {
            countsLabel.setText("Toplam: " + total + "  •  Boş: " + empty
                    + "  •  Dolu: " + occupied);
        }

        int shown = 0;
        for (Integer tableNo : tableNumbers) {
            TableOrderStatus st = appState.snapshot(tableNo).getStatus();
            boolean isEmpty = (st == null || st == TableOrderStatus.EMPTY);
            // Filter
            if (activeFilter == StatusFilter.EMPTY && !isEmpty) continue;
            if (activeFilter == StatusFilter.OCCUPIED && isEmpty) continue;
            JButton button = createTableButton(tableNo);
            grid.add(button);
            shown++;
        }

        if (shown == 0) {
            JLabel empty1 = new JLabel("Bu filtreye uyan masa yok",
                    SwingConstants.CENTER);
            empty1.setForeground(Color.GRAY);
            empty1.setFont(empty1.getFont().deriveFont(Font.ITALIC, 14f));
            empty1.setAlignmentX(Component.LEFT_ALIGNMENT);
            empty1.setBorder(BorderFactory.createEmptyBorder(40, 0, 40, 0));
            wrapper.add(empty1);
        } else {
            wrapper.add(grid);
        }
        return wrapper;
    }

    private JButton createTableButton(int tableNo) {
        JButton button = new JButton();
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBackground(Color.RED);
        Dimension preferredSize = new Dimension(150, 100);
        button.setPreferredSize(preferredSize);
        button.setMinimumSize(preferredSize);
        button.addActionListener(e -> openTableDialog(tableNo));
        tableButtons.put(tableNo, button);
        refreshButton(tableNo);
        return button;
    }

    private void openTableDialog(int tableNo) {
        TableSnapshot snapshot = appState.snapshot(tableNo);
        TableOrderDialog dialog = new TableOrderDialog(
                SwingUtilities.getWindowAncestor(this), appState, snapshot, currentUser
        );
        dialog.setVisible(true);
    }

    private void handleStateChange(PropertyChangeEvent event) {
        if (!AppState.EVENT_TABLES.equals(event.getPropertyName())) {
            return;
        }
        Object newValue = event.getNewValue();
        if (newValue instanceof Integer) {
            int tableNo = (Integer) newValue;
            SwingUtilities.invokeLater(() -> {
                refreshButton(tableNo);
                // Boş/Dolu filtresi aktifse → global görünüm değişebilir, yenile
                if (activeFilter != StatusFilter.ALL) {
                    refreshGrid();
                } else if (currentArea != null
                        && currentArea.getTableNumbers().contains(tableNo)) {
                    refreshGrid();
                }
            });
        }
    }

    private void refreshButton(int tableNo) {
        JButton button = tableButtons.get(tableNo);
        if (button == null) return;

        TableSnapshot snapshot = appState.snapshot(tableNo);
        button.setText(formatText(snapshot));

        // RENKLER buradan geliyor
        button.setBackground(colorFor(snapshot.getStatus()));
        button.setForeground(Color.DARK_GRAY);

        button.setBorder(BorderFactory.createLineBorder(Color.BLACK));
    }

    private String formatText(TableSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("Masa ").append(snapshot.getTableNo());
        BigDecimal total = snapshot.getTotal();
        if (total != null && total.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("<br/><b>").append(currencyFormat.format(total)).append("</b>");
        }
        return "<html><center>" + sb + "</center></html>";
    }

    private Color colorFor(TableOrderStatus status) {
        if (status == null) {
            return Color.WHITE;
        }
        return switch (status) {
            case EMPTY   -> new Color(245, 246, 216);
            case ORDERED -> new Color(155, 203, 239);
            case SERVED  -> new Color(165, 214, 167);
        };
    }


    public boolean canPerformSale() {
        Role role = currentUser.getRole();
        return role == Role.ADMIN || role == Role.KASIYER;
    }

    // ============================================================
    //   Yardımcılar
    // ============================================================

    private static String salonKey(String building, String floor, String salon) {
        return safe(building) + "||" + safe(floor) + "||" + safe(salon);
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        appState.removePropertyChangeListener(listener);
    }

    // ============================================================
    //   Scrollable interface — DIŞ JScrollPane bu paneli viewport boyutuna
    //   sıkıştırsın diye. Aksi takdirde dış pane içeriği komple alır, iç
    //   rightScroll'un scroll yapacak alanı kalmaz.
    // ============================================================

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 32;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return Math.max(120, visibleRect.height - 32);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;   // genişlik viewport'a uyar — dış scroll yatayda olmasın
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return true;   // KRİTİK: yükseklik viewport'a uyar → dış scroll dikeyde
                       // çalışmaz, iç rightScroll devreye girer
    }

    // ============================================================
    //   ScrollablePanel — JScrollPane'in viewport'unda doğru scroll davranışı
    // ============================================================

    /**
     * Scrollable interface'i doğru implement eden JPanel.
     * <p>tracksViewportWidth=true → yatay scroll çıkmaz, panel genişlik viewport'a uyar.
     * <p>tracksViewportHeight=false → panel preferred yüksekliği ile dikey scroll oluşur.
     * <p>unitIncrement / blockIncrement → mouse wheel ve Page Up/Down hızı.
     */
    static class ScrollablePanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 32;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(120, visibleRect.height - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;  // yatay scroll yok, genişlik viewport'a uyar
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false; // dikey scroll var, panel kendi yüksekliğini kullanır
        }
    }
}
