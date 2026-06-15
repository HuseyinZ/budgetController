package UI;

import model.User;
import model.UserAreaPermission;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bir garsonun erişebileceği (bina, salon) çiftlerini düzenleme dialogu.
 *
 * <p>Sol tarafta tüm tanımlı alanlar checkbox listesi olarak görünür;
 * mevcut izinler işaretlidir. "Kaydet"e basıldığında tüm önceki izinler
 * silinir, sadece işaretli olanlar atanır.
 */
public class UserAreaPermissionsDialog extends JDialog {

    private final AppState appState;
    private final User targetUser;
    private final List<JCheckBox> boxes = new ArrayList<>();

    public UserAreaPermissionsDialog(Window owner, AppState appState, User targetUser) {
        super(owner, "Alan Yetkileri — " + safeName(targetUser), ModalityType.APPLICATION_MODAL);
        this.appState = appState;
        this.targetUser = targetUser;

        setLayout(new BorderLayout(8, 8));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        add(buildHeader(), BorderLayout.NORTH);
        add(new JScrollPane(buildAreasList()), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);

        setPreferredSize(new Dimension(420, 520));
        pack();
        setLocationRelativeTo(owner);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 4));
        header.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        JLabel title = new JLabel("İzin verilecek kat / salonlar:");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        header.add(title, BorderLayout.NORTH);

        JLabel hint = new JLabel(
                "<html><i>Sadece işaretlenen alanlar bu garsona görünür."
              + " İşaretsiz kalan alanlar gizlenir.</i></html>");
        hint.setForeground(Color.GRAY);
        header.add(hint, BorderLayout.CENTER);
        return header;
    }

    private JComponent buildAreasList() {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        // Mevcut izinler
        Set<String> currentKeys = new HashSet<>();
        for (UserAreaPermission p : appState.getPermissionsFor(targetUser.getId())) {
            currentKeys.add(AppState.areaKey(p.getBuilding(), p.getSection()));
        }

        // Tüm tanımlı alanlar — yetkilendirme KAT seviyesinde olduğu için
        // aynı bina+kat çiftini sadece bir kez göster (her kat tüm salonları kapsar).
        Set<String> seen = new HashSet<>();
        for (AppState.AreaDefinition area : appState.getAllAreas()) {
            String key = AppState.areaKey(area.getBuilding(), area.getSection());
            if (!seen.add(key)) {
                continue; // bu bina+kat çifti zaten eklenmiş (farklı salonları var)
            }
            String label = area.getBuilding() + " — " + area.getSection();
            JCheckBox cb = new JCheckBox(label);
            cb.putClientProperty("areaKey", key);
            cb.setSelected(currentKeys.contains(key));
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(cb);
            boxes.add(cb);
        }
        if (boxes.isEmpty()) {
            JLabel empty = new JLabel("(Sistemde tanımlı bina/salon yok)");
            empty.setForeground(Color.GRAY);
            panel.add(empty);
        }

        // Hızlı seçim butonları
        JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton selectAll = new JButton("Tümünü seç");
        JButton clearAll = new JButton("Tümünü temizle");
        selectAll.addActionListener(e -> boxes.forEach(b -> b.setSelected(true)));
        clearAll.addActionListener(e -> boxes.forEach(b -> b.setSelected(false)));
        quick.add(selectAll);
        quick.add(clearAll);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(panel, BorderLayout.CENTER);
        wrapper.add(quick, BorderLayout.SOUTH);
        return wrapper;
    }

    private JComponent buildButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
        JButton cancel = new JButton("İptal");
        cancel.addActionListener(e -> dispose());
        JButton save = new JButton("Kaydet");
        save.addActionListener(e -> saveAndClose());
        buttons.add(cancel);
        buttons.add(save);
        return buttons;
    }

    private void saveAndClose() {
        Set<String> selected = new HashSet<>();
        for (JCheckBox cb : boxes) {
            if (cb.isSelected()) {
                Object key = cb.getClientProperty("areaKey");
                if (key instanceof String s) selected.add(s);
            }
        }
        try {
            appState.replaceAreaPermissions(targetUser.getId(), selected);
            JOptionPane.showMessageDialog(this,
                    selected.size() + " alan atandı.",
                    "Kaydedildi", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (RuntimeException ex) {
            JOptionPane.showMessageDialog(this,
                    "Yetkiler kaydedilemedi: " + ex.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String safeName(User u) {
        if (u == null) return "?";
        String name = u.getFullName();
        if (name == null || name.isBlank()) name = u.getUsername();
        return name == null ? "?" : name;
    }
}
