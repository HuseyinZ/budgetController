package UI;

import model.ProductUnit;
import state.AppState;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.util.List;

/**
 * Ürün birimi combo'larının TEK kaynağı — {@code product_units} tablosu (V004).
 *
 * <p>Hem {@link ProductsPanel} hem {@link ProductEditDialog} bunu kullanır;
 * iki ekran farklı liste üretemez. Liste kodda hardcoded DEĞİLDİR ve
 * okuma başarısız olursa <b>gömülü yedek listeye düşülmez</b>: hata
 * kullanıcıya gösterilir ve combo boş kalır, böylece DB'de olmayan bir birimle
 * ürün kaydedilemez.
 */
public final class ProductUnitOptions {

    /** Yeni üründe tercih edilen birim — DB'de varsa seçilir. */
    public static final String DEFAULT_CODE = "porsiyon";

    private ProductUnitOptions() {
    }

    /**
     * Combo'yu aktif birimlerle doldurur ve serbest metin girişini kapatır.
     *
     * @param combo    doldurulacak combo
     * @param owner    hata diyalogu için üst bileşen (null olabilir)
     * @param selected korunacak mevcut değer; listede yoksa listeye eklenir
     *                 (eski bir ürünün birimi sessizce değişmesin)
     */
    public static void load(JComboBox<String> combo, Component owner, String selected) {
        combo.setEditable(false);
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        try {
            List<ProductUnit> units = AppState.getInstance().getActiveProductUnits();
            for (ProductUnit unit : units) {
                String code = unit.getCode();
                if (code != null && !code.isBlank() && model.getIndexOf(code) < 0) {
                    model.addElement(code);
                }
            }
        } catch (RuntimeException ex) {
            combo.setModel(new DefaultComboBoxModel<>());
            JOptionPane.showMessageDialog(owner,
                    "Birim listesi veritabanından okunamadı.\n"
                            + "Birim seçilemeyeceği için ürün kaydedilemez.\n"
                            + "(Teknik ayrıntılar logs/errors.log dosyasında)",
                    "Hata", JOptionPane.ERROR_MESSAGE);
            return;
        }
        combo.setModel(model);
        select(combo, selected);
    }

    /**
     * Var olan değeri seçer. Değer listede yoksa (örn. sonradan pasifleştirilmiş
     * bir birim) listeye eklenir — düzenleme sırasında ürünün birimi kaybolmasın.
     * Değer boşsa {@link #DEFAULT_CODE}, o da yoksa ilk öğe seçilir.
     */
    public static void select(JComboBox<String> combo, String value) {
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) combo.getModel();
        if (value != null && !value.isBlank()) {
            if (model.getIndexOf(value) < 0) {
                model.addElement(value);
            }
            combo.setSelectedItem(value);
            return;
        }
        if (model.getIndexOf(DEFAULT_CODE) >= 0) {
            combo.setSelectedItem(DEFAULT_CODE);
        } else if (model.getSize() > 0) {
            combo.setSelectedIndex(0);
        } else {
            combo.setSelectedItem(null);
        }
    }
}
