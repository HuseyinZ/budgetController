package UI;

import model.Category;
import model.KitchenPrinter;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kategori → Mutfak Yazıcısı eşleştirme dialogu.
 *
 * <p>Solda kategori listesi, sağda seçili kategori için aktif yazıcıların
 * checkbox listesi. Bir kategori birden fazla yazıcıya da gönderilebilir
 * (örn. soğuk başlangıç hem ortak hazırlık hem servis ekibine).
 *
 * <p>Kaydet butonu basıldığında seçili kategorinin eski atamaları silinir
 * ve yeni atamalar yazılır.
 *
 * <p>Erişim: Admin + Kasiyer (AdminPanel üzerinden açılır).
 */
public class CategoryPrinterRoutingDialog extends JDialog {

    private final AppState appState;
    private final DefaultListModel<Category> catModel = new DefaultListModel<>();
    private final JList<Category> categoryList = new JList<>(catModel);
    private final JPanel printerPanel = new JPanel();
    private final List<JCheckBox> printerBoxes = new ArrayList<>();
    private final JLabel headerLabel = new JLabel("Kategori seçiniz");
    private List<KitchenPrinter> printers = List.of();
    private Category currentCategory;

    public CategoryPrinterRoutingDialog(Window owner, AppState appState) {
        super(owner, "Mutfak Eşleştirme — Kategori → Yazıcı",
                ModalityType.APPLICATION_MODAL);
        this.appState = appState;
        setLayout(new BorderLayout(8, 8));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        add(buildLeft(), BorderLayout.WEST);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        loadCategories();
        loadPrinters();

        setPreferredSize(new Dimension(820, 540));
        pack();
        setLocationRelativeTo(owner);
    }

    private JComponent buildLeft() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(8, 8, 8, 4),
                BorderFactory.createTitledBorder("Kategoriler")));
        categoryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        categoryList.setCellRenderer(new CategoryRenderer());
        categoryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelected();
        });
        JScrollPane sp = new JScrollPane(categoryList);
        sp.setPreferredSize(new Dimension(240, 400));
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildCenter() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(8, 4, 8, 8),
                BorderFactory.createTitledBorder("Bu kategori için yazıcılar")));
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 14f));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        p.add(headerLabel, BorderLayout.NORTH);

        printerPanel.setLayout(new BoxLayout(printerPanel, BoxLayout.Y_AXIS));
        printerPanel.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        p.add(new JScrollPane(printerPanel), BorderLayout.CENTER);

        // Hızlı butonlar
        JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton selectAll = new JButton("Tümünü seç");
        JButton clearAll  = new JButton("Tümünü temizle");
        selectAll.addActionListener(e -> printerBoxes.forEach(b -> b.setSelected(true)));
        clearAll.addActionListener(e -> printerBoxes.forEach(b -> b.setSelected(false)));
        quick.add(selectAll);
        quick.add(clearAll);
        p.add(quick, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildFooter() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        JButton close = new JButton("Kapat");
        close.setPreferredSize(new Dimension(120, 44));
        close.addActionListener(e -> dispose());
        JButton save = new JButton("Kaydet");
        save.setPreferredSize(new Dimension(140, 44));
        save.addActionListener(e -> saveCurrent());
        buttons.add(close);
        buttons.add(save);
        return buttons;
    }

    // ---- veri ----

    private void loadCategories() {
        catModel.clear();
        List<Category> cats = appState.getAllCategories();
        cats.stream()
                .filter(c -> c != null && c.isActive())
                .sorted(Comparator.comparing(c -> c.getName() == null ? "" : c.getName().toLowerCase()))
                .forEach(catModel::addElement);
        if (!catModel.isEmpty()) categoryList.setSelectedIndex(0);
    }

    private void loadPrinters() {
        printers = appState.getAllKitchenPrinters();
    }

    private void showSelected() {
        currentCategory = categoryList.getSelectedValue();
        printerPanel.removeAll();
        printerBoxes.clear();

        if (currentCategory == null) {
            headerLabel.setText("Kategori seçiniz");
            printerPanel.revalidate();
            printerPanel.repaint();
            return;
        }

        headerLabel.setText("Kategori: " + currentCategory.getName());

        Set<Integer> linked = new HashSet<>(
                appState.getPrinterIdsForCategory(currentCategory.getId()));

        if (printers.isEmpty()) {
            JLabel empty = new JLabel(
                    "(Sistemde tanımlı aktif mutfak yazıcısı yok)");
            empty.setForeground(Color.GRAY);
            printerPanel.add(empty);
        } else {
            for (KitchenPrinter pr : printers) {
                int id = pr.getId() == null ? 0 : pr.getId().intValue();
                JCheckBox cb = new JCheckBox(
                        pr.getDisplayName() + "   [" + pr.getCode() + " @ " + pr.getHost() + "]");
                cb.setFont(cb.getFont().deriveFont(Font.PLAIN, 14f));
                cb.putClientProperty("printerId", id);
                cb.setSelected(linked.contains(id));
                cb.setAlignmentX(Component.LEFT_ALIGNMENT);
                cb.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
                printerBoxes.add(cb);
                printerPanel.add(cb);
            }
        }
        printerPanel.revalidate();
        printerPanel.repaint();
    }

    private void saveCurrent() {
        if (currentCategory == null) {
            JOptionPane.showMessageDialog(this, "Önce kategori seçin",
                    "Uyarı", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Set<Integer> picked = new HashSet<>();
        for (JCheckBox cb : printerBoxes) {
            if (cb.isSelected()) {
                Object pid = cb.getClientProperty("printerId");
                if (pid instanceof Integer i) picked.add(i);
            }
        }
        try {
            appState.replaceCategoryRoutes(currentCategory.getId(), picked);
            JOptionPane.showMessageDialog(this,
                    currentCategory.getName() + " için " + picked.size() +
                            " yazıcı atandı.",
                    "Kaydedildi", JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this,
                    "Atama kaydedilemedi: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---- render ----

    private class CategoryRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Category cat) {
                int n = appState.getPrinterIdsForCategory(cat.getId()).size();
                String name = cat.getName() == null ? "Kategori" : cat.getName();
                String suffix = n > 0 ? "  (" + n + ")" : "  ·";
                ((JLabel) c).setText(name + suffix);
                if (!isSelected) {
                    c.setForeground(n > 0 ? new Color(20, 80, 30) : Color.DARK_GRAY);
                }
            }
            return c;
        }
    }
}
