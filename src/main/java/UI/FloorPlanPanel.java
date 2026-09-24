package UI;

import model.TablePlacement;

import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * "Katlar" görünümünde bir alanın masalarını KAYDEDİLMİŞ yerleşime göre çizer.
 *
 * <p><b>Ölçek:</b> yerleşimler 0-1000 normalize uzaydadır. Plan, küçük bir
 * kenar boşluğu dışında görünüm alanının TAMAMINI kullanır: yatay ölçek
 * {@code (genişlik - 2·kenar) / 1000}, dikey ölçek {@code (yükseklik - 2·kenar) / 1000}
 * ayrı ayrı hesaplanır. Böylece geniş ekranda yan boşluklar oluşmaz ve masalar
 * olabildiğince büyük çizilir; masaların göreli düzeni (merkezleri) korunur.
 *
 * <p><b>Şekil:</b> RECT/OVAL görünümle birlikte esner. SQUARE kare, ROUND
 * daire kalır: kenar, iki eksendeki ölçülerin küçüğüdür ve şekil kaydedilen
 * kutunun merkezine oturtulur.
 *
 * <p><b>Kaydırma:</b> yalnız görünüm GERÇEKTEN küçükse — yani en küçük masa
 * {@link #MIN_TABLE_PX} altına düşecekse — plan küçülmeyi bırakır ve kayar.
 *
 * <p>Bu panel veriye dokunmaz: yerleşimler {@code AppState} anlık
 * görüntüsünden gelir.
 */
final class FloorPlanPanel extends JPanel implements Scrollable {

    /** Normalize uzayın kenar uzunluğu. */
    static final int SPACE = 1000;
    /** Planın kenarlardan boşluğu (px) — küçük tutulur. */
    static final int PADDING = 8;
    /** Bir masanın kısa kenarı bundan küçük çizilmez; altında kaydırma çıkar (px). */
    static final int MIN_TABLE_PX = 44;
    /** Masasız/çok seyrek planlar için alt sınır (px). */
    static final int MIN_VIEW_WIDTH = 360;
    static final int MIN_VIEW_HEIGHT = 260;

    private static final Color FLOOR_FILL = new Color(250, 250, 247);
    private static final Color FLOOR_EDGE = new Color(225, 225, 220);

    private final Map<Component, TablePlacement> placements = new IdentityHashMap<>();

    FloorPlanPanel() {
        setLayout(new FloorPlanLayout());
        setOpaque(true);
        setBackground(Color.WHITE);
    }

    /**
     * Bir masa bileşeni ekler. {@code resolved} konumlu olmalıdır (konumsuzlar
     * önceden {@code LayoutAutoArrange.resolve} ile geçici yuva almış olmalı).
     */
    void addTable(Component component, TablePlacement resolved) {
        if (resolved == null || !resolved.isPlaced()) {
            throw new IllegalArgumentException("Yerleşim çözümlenmemiş: " + resolved);
        }
        placements.put(component, resolved);
        add(component);
    }

    TablePlacement placementOf(Component component) {
        return placements.get(component);
    }

    // ------------------------------------------------------------------
    //  Saf geometri — test edilebilir
    // ------------------------------------------------------------------

    /** Yatay: normalize birim başına piksel. */
    static double scaleX(int viewWidth) {
        int usable = viewWidth - 2 * PADDING;
        return usable <= 0 ? 0 : usable / (double) SPACE;
    }

    /** Dikey: normalize birim başına piksel. */
    static double scaleY(int viewHeight) {
        int usable = viewHeight - 2 * PADDING;
        return usable <= 0 ? 0 : usable / (double) SPACE;
    }

    /**
     * Döndürülmemiş masa dikdörtgeni (piksel). Plan görünüm alanını küçük
     * kenar boşluğu dışında tamamen kaplar. SQUARE/ROUND için kenar iki
     * eksenin küçüğüdür ve kaydedilen kutunun merkezine oturtulur.
     */
    static Rectangle toPixels(TablePlacement p, int viewWidth, int viewHeight) {
        double sx = scaleX(viewWidth);
        double sy = scaleY(viewHeight);
        double x = PADDING + p.posX() * sx;
        double y = PADDING + p.posY() * sy;
        double w = p.width() * sx;
        double h = p.height() * sy;

        if (keepsAspect(p)) {
            double side = Math.min(w, h);
            x += (w - side) / 2;
            y += (h - side) / 2;
            w = side;
            h = side;
        }
        return new Rectangle((int) Math.round(x), (int) Math.round(y),
                Math.max(1, (int) Math.round(w)), Math.max(1, (int) Math.round(h)));
    }

    /** Görünümle esnemeyen, oranı korunan şekiller. */
    static boolean keepsAspect(TablePlacement p) {
        return "SQUARE".equals(p.shape()) || "ROUND".equals(p.shape());
    }

    /**
     * Döndürülmüş şeklin eksenle hizalı sınır kutusu — bileşen bu kutuya
     * yerleştirilir ki köşeler kırpılmasın. Merkez korunur.
     */
    static Rectangle rotatedBounds(Rectangle base, int rotationDeg) {
        int deg = ((rotationDeg % 360) + 360) % 360;
        if (deg == 0) {
            return new Rectangle(base);
        }
        double rad = Math.toRadians(deg);
        double cos = Math.abs(Math.cos(rad));
        double sin = Math.abs(Math.sin(rad));
        // Küçük tolerans: cos(90°) = 6e-17 gibi kayan nokta artıkları 90°'de
        // kutuyu 1 px büyütmesin (90° dönüş tam olarak genişlik/yükseklik takasıdır).
        int w = (int) Math.ceil(base.width * cos + base.height * sin - 1e-9);
        int h = (int) Math.ceil(base.width * sin + base.height * cos - 1e-9);
        int cx = base.x + base.width / 2;
        int cy = base.y + base.height / 2;
        return new Rectangle(cx - w / 2, cy - h / 2, w, h);
    }

    /**
     * En küçük masanın kısa kenarı {@link #MIN_TABLE_PX} olacak şekilde gereken
     * en küçük plan boyutu. Görünüm bundan küçükse plan küçülmez, kayar.
     */
    static Dimension minimumReadableSize(Collection<TablePlacement> tables) {
        double needX = 0;
        double needY = 0;
        for (TablePlacement p : tables) {
            if (p == null || !p.isPlaced()) {
                continue;
            }
            needX = Math.max(needX, MIN_TABLE_PX / (double) Math.max(1, p.width()));
            needY = Math.max(needY, MIN_TABLE_PX / (double) Math.max(1, p.height()));
        }
        int width = (int) Math.ceil(SPACE * needX) + 2 * PADDING;
        int height = (int) Math.ceil(SPACE * needY) + 2 * PADDING;
        return new Dimension(Math.max(MIN_VIEW_WIDTH, width), Math.max(MIN_VIEW_HEIGHT, height));
    }

    // ------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth() - 2 * PADDING;
        int h = getHeight() - 2 * PADDING;
        if (w <= 0 || h <= 0) {
            return;
        }
        g.setColor(FLOOR_FILL);
        g.fillRect(PADDING, PADDING, w, h);
        g.setColor(FLOOR_EDGE);
        g.drawRect(PADDING, PADDING, w, h);
    }

    /** Normalize yerleşimi her yeniden boyutlandırmada piksele çevirir. */
    private final class FloorPlanLayout implements LayoutManager {
        @Override public void addLayoutComponent(String name, Component comp) { }
        @Override public void removeLayoutComponent(Component comp) {
            placements.remove(comp);
        }

        @Override public Dimension preferredLayoutSize(Container parent) {
            return minimumReadableSize(placements.values());
        }

        @Override public Dimension minimumLayoutSize(Container parent) {
            return minimumReadableSize(placements.values());
        }

        @Override public void layoutContainer(Container parent) {
            int width = parent.getWidth();
            int height = parent.getHeight();
            for (Component c : parent.getComponents()) {
                TablePlacement p = placements.get(c);
                if (p == null) {
                    continue;
                }
                Rectangle base = toPixels(p, width, height);
                c.setBounds(rotatedBounds(base, p.rotationDeg()));
                if (c instanceof FloorTableButton button) {
                    button.setShapeGeometry(p.shape(), p.rotationDeg(), base.width, base.height);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Scrollable: yeterince büyükse görünümü doldurur, değilse o eksende kayar
    // ------------------------------------------------------------------

    @Override public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 24;
    }

    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return 120;
    }

    @Override public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport vp && vp.getWidth() >= getPreferredSize().width;
    }

    @Override public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport vp && vp.getHeight() >= getPreferredSize().height;
    }
}
