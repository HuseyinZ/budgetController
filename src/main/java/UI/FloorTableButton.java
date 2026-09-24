package UI;

import model.TablePlacement;

import javax.swing.JButton;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RectangularShape;

/**
 * Kat planındaki masa: kaydedilmiş ŞEKİL ve DÖNÜŞ ile çizilen bir
 * {@link JButton}.
 *
 * <p>Hâlâ gerçek bir JButton'dır: tıklama (ActionListener), metin (masa no +
 * toplam), arka plan rengi (durum) mevcut kodla aynen çalışır. Değişen yalnız
 * çizim ve tıklama alanıdır:
 * <ul>
 *   <li>RECT / SQUARE → dikdörtgen, ROUND / OVAL → elips (editörle aynı).</li>
 *   <li>Şekil, merkezi etrafında döndürülerek çizilir; metin okunaklı kalsın
 *       diye DÜZ kalır.</li>
 *   <li>{@link #contains(int, int)} gerçek şekli kullanır: yuvarlak masanın
 *       köşesine veya döndürülmüş masanın boş kutusuna tıklamak masayı açmaz.</li>
 * </ul>
 */
final class FloorTableButton extends JButton {

    private String shape = TablePlacement.DEFAULT_SHAPE;
    private int rotationDeg;
    /** Döndürülmemiş şekil ölçüsü (px); 0 → bileşen boyutu kullanılır. */
    private int baseWidth;
    private int baseHeight;

    FloorTableButton() {
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setHorizontalAlignment(SwingConstants.CENTER);
        setVerticalAlignment(SwingConstants.CENTER);
        setMargin(new Insets(0, 0, 0, 0));
    }

    /** Yerleşim yöneticisi tarafından her yeniden boyutlandırmada çağrılır. */
    void setShapeGeometry(String shape, int rotationDeg, int baseWidth, int baseHeight) {
        this.shape = TablePlacement.normalizeShape(shape);
        this.rotationDeg = rotationDeg;
        this.baseWidth = Math.max(0, baseWidth);
        this.baseHeight = Math.max(0, baseHeight);
        float size = fontSizeFor(this.baseWidth, this.baseHeight);
        if (size > 0) {
            setFont(getFont().deriveFont(size));
        }
        repaint();
    }

    static final float MIN_FONT_PT = 11f;
    static final float MAX_FONT_PT = 18f;

    /**
     * Masa boyutuna göre okunaklı yazı boyu. İki satır (numara + toplam)
     * yüksekliğe, "Masa 101" genişliğe sığacak şekilde eksenler ayrı
     * değerlendirilir; 11-18 pt aralığında tutulur. 0 → ölçü bilinmiyor.
     */
    static float fontSizeFor(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) {
            return 0f;
        }
        float byWidth = widthPx / 7f;      // "Masa 101" ≈ 7 karakter genişliği
        float byHeight = heightPx / 3.2f;  // iki satır + satır aralığı
        return Math.max(MIN_FONT_PT, Math.min(MAX_FONT_PT, Math.min(byWidth, byHeight)));
    }

    String getShapeName() {
        return shape;
    }

    int getRotationDeg() {
        return rotationDeg;
    }

    /** Bileşen koordinatlarında gerçek masa şekli (dönüş uygulanmış). */
    Shape outline() {
        double w = baseWidth > 0 ? baseWidth : getWidth();
        double h = baseHeight > 0 ? baseHeight : getHeight();
        double cx = getWidth() / 2.0;
        double cy = getHeight() / 2.0;
        boolean elliptical = "ROUND".equals(shape) || "OVAL".equals(shape);
        RectangularShape base = elliptical
                ? new Ellipse2D.Double(cx - w / 2, cy - h / 2, w, h)
                : new Rectangle2D.Double(cx - w / 2, cy - h / 2, w, h);
        if (rotationDeg % 360 == 0) {
            return base;
        }
        return AffineTransform.getRotateInstance(Math.toRadians(rotationDeg), cx, cy)
                .createTransformedShape(base);
    }

    @Override
    public boolean contains(int x, int y) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return false;
        }
        return outline().contains(x, y);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Shape outline = outline();

        Color fill = getBackground() == null ? Color.WHITE : getBackground();
        if (getModel().isPressed()) {
            fill = fill.darker();
        }
        g2.setColor(fill);
        g2.fill(outline);

        g2.setColor(getModel().isRollover() ? new Color(30, 30, 30) : Color.BLACK);
        g2.setStroke(new BasicStroke(getModel().isRollover() ? 2.2f : 1.4f));
        g2.draw(outline);
        g2.dispose();

        // contentAreaFilled=false → üst sınıf yalnız metni çizer (düz, okunaklı)
        super.paintComponent(g);
    }
}
