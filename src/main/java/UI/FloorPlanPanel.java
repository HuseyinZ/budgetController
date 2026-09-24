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
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * "Katlar" görünümünde bir alanın masalarını KAYDEDİLMİŞ yerleşime göre çizer.
 *
 * <p>Yerleşimler 0-1000 normalize uzaydadır. Yatay ve dikey ölçek AYNIDIR
 * ({@code min(genişlik, yükseklik) / 1000}) — editörle birebir aynı ölçek,
 * böylece kare masa kare, yuvarlak masa yuvarlak kalır. Plan görünüm alanında
 * ortalanır. Pencere boyutu değişince {@link FloorPlanLayout} tüm masaları
 * yeniden ölçekler; göreli düzen korunur.
 *
 * <p>Bu panel veriye dokunmaz: yerleşimler çağıran tarafından
 * ({@code AppState} anlık görüntüsünden) verilir.
 */
final class FloorPlanPanel extends JPanel implements Scrollable {

    /** Normalize uzayın kenar uzunluğu. */
    static final int SPACE = 1000;
    /** Planın kenarlardan boşluğu (px). */
    static final int PADDING = 12;
    /** Bu boyutun altında plan küçülmez, kaydırma çubuğu çıkar (px). */
    static final int MIN_VIEW = 420;

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

    /** Normalize birim başına piksel; en-boy oranı korunur. */
    static double scaleFor(int viewWidth, int viewHeight) {
        int usable = Math.min(viewWidth, viewHeight) - 2 * PADDING;
        return usable <= 0 ? 0 : usable / (double) SPACE;
    }

    /**
     * Döndürülmemiş masa dikdörtgeni (piksel). Plan görünüm alanında ortalanır.
     */
    static Rectangle toPixels(TablePlacement p, int viewWidth, int viewHeight) {
        double s = scaleFor(viewWidth, viewHeight);
        int side = (int) Math.round(SPACE * s);
        int originX = (viewWidth - side) / 2;
        int originY = (viewHeight - side) / 2;
        int x = originX + (int) Math.round(p.posX() * s);
        int y = originY + (int) Math.round(p.posY() * s);
        int w = Math.max(1, (int) Math.round(p.width() * s));
        int h = Math.max(1, (int) Math.round(p.height() * s));
        return new Rectangle(x, y, w, h);
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

    // ------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        double s = scaleFor(getWidth(), getHeight());
        int side = (int) Math.round(SPACE * s);
        if (side <= 0) {
            return;
        }
        int x = (getWidth() - side) / 2;
        int y = (getHeight() - side) / 2;
        g.setColor(FLOOR_FILL);
        g.fillRect(x, y, side, side);
        g.setColor(FLOOR_EDGE);
        g.drawRect(x, y, side, side);
    }

    /** Normalize yerleşimi her yeniden boyutlandırmada piksele çevirir. */
    private final class FloorPlanLayout implements LayoutManager {
        @Override public void addLayoutComponent(String name, Component comp) { }
        @Override public void removeLayoutComponent(Component comp) {
            placements.remove(comp);
        }

        @Override public Dimension preferredLayoutSize(Container parent) {
            return new Dimension(MIN_VIEW, MIN_VIEW);
        }

        @Override public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(MIN_VIEW, MIN_VIEW);
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
    //  Scrollable: yeterince büyükse görünüme sığar (ölçeklenir), değilse kayar
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
        return getParent() instanceof JViewport vp && vp.getWidth() >= MIN_VIEW;
    }

    @Override public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport vp && vp.getHeight() >= MIN_VIEW;
    }
}
