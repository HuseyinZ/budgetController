package UI;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bir kaleme not eklemek için dokunmatik dostu dialog.
 *
 * <p>İki bölüm:
 * <ul>
 *   <li>Sık kullanılan toggle'lar: Soğansız, Tuzsuz, Bibersiz, vs. (büyük butonlar)</li>
 *   <li>Serbest metin alanı (extra notlar için)</li>
 * </ul>
 *
 * <p>Sonuç: seçili toggle'lar virgül ile birleşir, alttaki metin de eklenir.
 * Örn: "Soğansız, Az pişmiş - acı sos olsun".
 */
public class ProductNoteDialog extends JDialog {

    /** Hazır toggle seçenekleri — anahtar: etiket, değer: eklenecek metin */
    private static final Map<String, String> PRESETS = new LinkedHashMap<>();
    static {
        PRESETS.put("Soğansız",    "Soğansız");
        PRESETS.put("Tuzsuz",      "Tuzsuz");
        PRESETS.put("Bibersiz",    "Bibersiz");
        PRESETS.put("Acılı",       "Acılı");
        PRESETS.put("Acısız",      "Acısız");
        PRESETS.put("Az pişmiş",   "Az pişmiş");
        PRESETS.put("Çok pişmiş",  "Çok pişmiş");
        PRESETS.put("Yağsız",      "Yağsız");
        PRESETS.put("Ekstra sos",  "Ekstra sos");
    }

    private final List<JToggleButton> toggles = new ArrayList<>();
    private final JTextField freeText = new JTextField(24);
    private String result;   // null → iptal

    public ProductNoteDialog(Window owner, String productName, String initialNote) {
        super(owner, "Not — " + (productName == null ? "Ürün" : productName),
                ModalityType.APPLICATION_MODAL);
        setLayout(new BorderLayout(8, 8));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        add(buildHeader(productName), BorderLayout.NORTH);
        add(buildToggles(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        // Mevcut notu (varsa) işaretle
        if (initialNote != null && !initialNote.isBlank()) {
            String[] parts = initialNote.split(",");
            StringBuilder leftover = new StringBuilder();
            for (String p : parts) {
                String token = p.trim();
                boolean matched = selectMatchingPreset(token);
                if (!matched && !token.isEmpty()) {
                    if (leftover.length() > 0) leftover.append(", ");
                    leftover.append(token);
                }
            }
            freeText.setText(leftover.toString());
        }

        setPreferredSize(new Dimension(640, 480));
        pack();
        setLocationRelativeTo(owner);
    }

    private boolean selectMatchingPreset(String token) {
        for (JToggleButton tb : toggles) {
            if (token.equalsIgnoreCase(tb.getText())) {
                tb.setSelected(true);
                return true;
            }
        }
        return false;
    }

    private JComponent buildHeader(String productName) {
        JLabel l = new JLabel("<html><div style='padding:8px;text-align:center;'>"
                + "<b>" + (productName == null ? "Ürün" : productName) + "</b><br/>"
                + "<span style='color:#888;'>Aşağıdaki seçeneklerden istediklerinizi seçin"
                + " veya alta serbest not yazın.</span></div></html>",
                SwingConstants.CENTER);
        return l;
    }

    private JComponent buildToggles() {
        JPanel grid = new JPanel(new GridLayout(0, 3, 8, 8));
        grid.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        for (String label : PRESETS.keySet()) {
            JToggleButton tb = new JToggleButton(label);
            tb.setPreferredSize(new Dimension(160, 60));
            tb.setFont(tb.getFont().deriveFont(Font.BOLD, 15f));
            toggles.add(tb);
            grid.add(tb);
        }
        return grid;
    }

    private JComponent buildFooter() {
        JPanel south = new JPanel(new BorderLayout(8, 4));
        south.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));

        JPanel free = new JPanel(new BorderLayout(8, 0));
        free.add(new JLabel("Ek not:"), BorderLayout.WEST);
        free.add(freeText, BorderLayout.CENTER);
        south.add(free, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton cancel = new JButton("İptal");
        cancel.setPreferredSize(new Dimension(120, 48));
        cancel.addActionListener(e -> { result = null; dispose(); });
        JButton clear = new JButton("Notu Temizle");
        clear.setPreferredSize(new Dimension(160, 48));
        clear.addActionListener(e -> { result = ""; dispose(); });
        JButton ok = new JButton("Kaydet");
        ok.setPreferredSize(new Dimension(120, 48));
        ok.addActionListener(e -> { result = buildNote(); dispose(); });
        buttons.add(clear);
        buttons.add(cancel);
        buttons.add(ok);
        south.add(buttons, BorderLayout.CENTER);
        return south;
    }

    private String buildNote() {
        StringBuilder sb = new StringBuilder();
        for (JToggleButton tb : toggles) {
            if (tb.isSelected()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(tb.getText());
            }
        }
        String free = freeText.getText() == null ? "" : freeText.getText().trim();
        if (!free.isEmpty()) {
            if (sb.length() > 0) sb.append(" — ");
            sb.append(free);
        }
        return sb.toString();
    }

    /** İletişim kutusunu görüntüler ve sonucu döndürür. */
    public String pickNote() {
        setVisible(true);
        return result;
    }
}
