package UI;

import model.TableLayoutEntry;
import service.layout.LayoutPlacementRules;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Masa düzeni tuvali — 0-1000 normalize uzayı piksele ölçekler.
 *
 * <p>Sürükleme yalnız BELLEKTE çalışır; kalıcılaştırma editör panelinin
 * "Kaydet" akışına aittir. Tuval hiçbir servis/DB çağrısı yapmaz.
 */
class LayoutCanvas extends JPanel {

    private static final int SPACE = LayoutPlacementRules.MAX_COORD;
    private static final Color ACTIVE_FILL = new Color(220, 247, 220);
    private static final Color INACTIVE_FILL = new Color(232, 232, 232);
    private static final Color DIRTY_BORDER = new Color(0, 120, 200);
    private static final Color SELECTED_BORDER = new Color(200, 80, 0);
    private static final Color GRID_COLOR = new Color(226, 226, 226);

    /** Ekranda gösterilen masalar (editörün çalışma kopyası). */
    private final List<TableLayoutEntry> tables = new ArrayList<>();
    private final java.util.Set<Integer> dirty = new java.util.HashSet<>();

    /** Izgara adımı (0-1000 normalize uzayda). 0 → ızgara kapalı. */
    private int gridStep = DEFAULT_GRID_STEP;
    /** Sürükleme sırasında ızgaraya yapış. */
    private boolean snapEnabled = true;
    /** Geçici serbest hareket (Shift basılı) — ayarları değiştirmez. */
    private boolean freeMoveOverride;

    static final int DEFAULT_GRID_STEP = 25;
    static final int MIN_GRID_STEP = 5;
    static final int MAX_GRID_STEP = 200;

    private Integer selectedTableNo;
    private TableLayoutEntry dragging;
    private int grabDx;
    private int grabDy;

    private IntConsumer selectionListener = tableNo -> { };
    private Consumer<TableLayoutEntry> movedListener = t -> { };

    LayoutCanvas() {
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(760, 560));
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { onPress(e); }
            @Override public void mouseDragged(MouseEvent e) { onDrag(e); }
            @Override public void mouseReleased(MouseEvent e) { onRelease(); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Izgara adımını değiştirir; değişiklik ANINDA uygulanır (çizim + yapışma). */
    void setGridStep(int step) {
        this.gridStep = Math.max(0, Math.min(step, MAX_GRID_STEP));
        repaint();
    }

    int getGridStep() {
        return gridStep;
    }

    void setSnapEnabled(boolean enabled) {
        this.snapEnabled = enabled;
    }

    boolean isSnapEnabled() {
        return snapEnabled;
    }

    /** Shift ile geçici serbest hareket — kalıcı ayarı değiştirmez. */
    void setFreeMoveOverride(boolean freeMove) {
        this.freeMoveOverride = freeMove;
    }

    /** Bu an için yapışma etkin mi? */
    boolean snapActive() {
        return snapEnabled && !freeMoveOverride && gridStep >= MIN_GRID_STEP;
    }

    /** Değeri en yakın ızgara çizgisine yuvarlar. */
    int snap(int value) {
        if (!snapActive()) {
            return value;
        }
        return Math.round(value / (float) gridStep) * gridStep;
    }

    void setSelectionListener(IntConsumer listener) {
        this.selectionListener = listener == null ? tableNo -> { } : listener;
    }

    void setMovedListener(Consumer<TableLayoutEntry> listener) {
        this.movedListener = listener == null ? t -> { } : listener;
    }

    /** Görüntülenecek masaları değiştirir; kirli işaretleri çağıran yönetir. */
    void setTables(List<TableLayoutEntry> newTables, java.util.Set<Integer> dirtyNumbers) {
        tables.clear();
        if (newTables != null) {
            tables.addAll(newTables);
        }
        dirty.clear();
        if (dirtyNumbers != null) {
            dirty.addAll(dirtyNumbers);
        }
        repaint();
    }

    void setSelected(Integer tableNo) {
        this.selectedTableNo = tableNo;
        repaint();
    }

    // ---------------- ölçekleme ----------------

    private double scale() {
        return Math.min(getWidth() / (double) SPACE, getHeight() / (double) SPACE);
    }

    private int toPixel(int normalized) {
        return (int) Math.round(normalized * scale());
    }

    private int toNormalized(int pixel) {
        double s = scale();
        return s <= 0 ? 0 : (int) Math.round(pixel / s);
    }

    // ---------------- etkileşim ----------------

    private void onPress(MouseEvent e) {
        TableLayoutEntry hit = tableAt(e.getX(), e.getY());
        selectedTableNo = hit == null ? null : hit.getTableNo();
        selectionListener.accept(selectedTableNo == null ? -1 : selectedTableNo);
        // PASİF masa sürüklenemez: konumu çakışma doğrulamasının dışında kalır,
        // sonradan aktifleştirilince iki aktif masa üst üste binebilirdi.
        // Seçilebilir (özellikleri görünür), ama konumu değişmez.
        if (hit != null && hit.isActive() && LayoutPlacementRules.isPlaced(hit)) {
            dragging = hit;
            grabDx = toNormalized(e.getX()) - hit.getPosX();
            grabDy = toNormalized(e.getY()) - hit.getPosY();
        }
        repaint();
    }

    private void onDrag(MouseEvent e) {
        if (dragging == null) {
            return;
        }
        // Önce ÖNERİLEN konum hesaplanır, sonra diğer aktif masalarla çakışma
        // denetlenir. Çakışıyorsa hareket reddedilir ve masa SON GEÇERLİ
        // konumunda kalır — geçersiz durum düzenleme sırasında hiç oluşmaz.
        // Shift → geçici serbest hareket; ayar kalıcı olarak değişmez.
        freeMoveOverride = e.isShiftDown();
        // Sıra: ham konum → ızgaraya yapış → sınıra clamp → çakışma denetimi
        int x = clamp(snap(toNormalized(e.getX()) - grabDx), dragging.getWidth());
        int y = clamp(snap(toNormalized(e.getY()) - grabDy), dragging.getHeight());
        if (x == dragging.getPosX() && y == dragging.getPosY()) {
            return;
        }
        if (wouldOverlap(dragging, x, y, dragging.getWidth(), dragging.getHeight())) {
            return;
        }
        dragging.setPosX(x);
        dragging.setPosY(y);
        dirty.add(dragging.getTableNo());
        repaint();
    }

    /**
     * Önerilen konum/ölçü, DİĞER aktif ve yerleştirilmiş masalardan biriyle
     * üst üste biner mi? Kenar teması çakışma sayılmaz (servis tarafındaki
     * {@code LayoutPlacementRules.findOverlap} ile aynı semantik).
     *
     * <p>Pasif masalar hesaba katılmaz: runtime düzen yalnız aktifleri yükler.
     */
    boolean wouldOverlap(TableLayoutEntry moving, int x, int y, int width, int height) {
        if (moving == null || !moving.isActive()) {
            return false;
        }
        int right = x + width;
        int bottom = y + height;
        for (TableLayoutEntry other : tables) {
            if (other == moving || other.getTableNo() == moving.getTableNo()) {
                continue;
            }
            if (!other.isActive() || !LayoutPlacementRules.isPlaced(other)) {
                continue;
            }
            int otherRight = other.getPosX() + other.getWidth();
            int otherBottom = other.getPosY() + other.getHeight();
            if (x < otherRight && other.getPosX() < right
                    && y < otherBottom && other.getPosY() < bottom) {
                return true;
            }
        }
        return false;
    }

    private void onRelease() {
        if (dragging != null) {
            movedListener.accept(dragging);
            dragging = null;
        }
    }

    /** Normalize uzayın dışına taşmayı engeller (doğrulama zaten reddederdi). */
    private static int clamp(int value, int size) {
        int max = LayoutPlacementRules.MAX_COORD - size;
        return Math.max(0, Math.min(value, Math.max(0, max)));
    }

    private TableLayoutEntry tableAt(int px, int py) {
        int nx = toNormalized(px);
        int ny = toNormalized(py);
        for (int i = tables.size() - 1; i >= 0; i--) {
            TableLayoutEntry t = tables.get(i);
            if (!LayoutPlacementRules.isPlaced(t)) {
                continue;
            }
            if (nx >= t.getPosX() && nx <= t.getPosX() + t.getWidth()
                    && ny >= t.getPosY() && ny <= t.getPosY() + t.getHeight()) {
                return t;
            }
        }
        return null;
    }

    // ---------------- çizim ----------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int edge = toPixel(SPACE);
        g2.setColor(new Color(245, 245, 245));
        g2.fillRect(0, 0, edge, edge);
        drawGrid(g2, edge);
        g2.setColor(new Color(210, 210, 210));
        g2.drawRect(0, 0, edge, edge);

        for (TableLayoutEntry t : tables) {
            if (!LayoutPlacementRules.isPlaced(t)) {
                continue;
            }
            drawTable(g2, t);
        }
        g2.dispose();
    }

    /**
     * İnce, açık gri, kesik ızgara çizgileri. Normalize uzayda hesaplanır, bu
     * yüzden pencere boyutu değişince ölçekle birlikte doğru yerde kalır.
     */
    private void drawGrid(Graphics2D g2, int edge) {
        if (gridStep < MIN_GRID_STEP) {
            return;
        }
        Graphics2D g = (Graphics2D) g2.create();
        g.setColor(GRID_COLOR);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10f, new float[]{3f, 4f}, 0f));
        for (int v = gridStep; v < SPACE; v += gridStep) {
            int p = toPixel(v);
            g.drawLine(p, 0, p, edge);
            g.drawLine(0, p, edge, p);
        }
        g.dispose();
    }

    private void drawTable(Graphics2D g2, TableLayoutEntry t) {
        int x = toPixel(t.getPosX());
        int y = toPixel(t.getPosY());
        int w = Math.max(toPixel(t.getWidth()), 4);
        int h = Math.max(toPixel(t.getHeight()), 4);

        Graphics2D g = (Graphics2D) g2.create();
        if (t.getRotationDeg() != 0) {
            g.rotate(Math.toRadians(t.getRotationDeg()), x + w / 2.0, y + h / 2.0);
        }
        g.setColor(t.isActive() ? ACTIVE_FILL : INACTIVE_FILL);
        String shape = t.getShape() == null ? "RECT" : t.getShape();
        if ("ROUND".equals(shape) || "OVAL".equals(shape)) {
            g.fillOval(x, y, w, h);
        } else {
            g.fillRect(x, y, w, h);
        }

        boolean isSelected = selectedTableNo != null && selectedTableNo == t.getTableNo();
        boolean isDirty = dirty.contains(t.getTableNo());
        g.setColor(isSelected ? SELECTED_BORDER : (isDirty ? DIRTY_BORDER : Color.GRAY));
        g.setStroke(new BasicStroke(isSelected || isDirty ? 2.5f : 1f));
        if ("ROUND".equals(shape) || "OVAL".equals(shape)) {
            g.drawOval(x, y, w, h);
        } else {
            g.drawRect(x, y, w, h);
        }

        g.setColor(t.isActive() ? Color.DARK_GRAY : Color.GRAY);
        String label = String.valueOf(t.getTableNo());
        int textWidth = g.getFontMetrics().stringWidth(label);
        g.drawString(label, x + (w - textWidth) / 2, y + h / 2 + 5);
        if (!t.isActive()) {
            String passive = "pasif";
            g.drawString(passive, x + (w - g.getFontMetrics().stringWidth(passive)) / 2, y + h / 2 + 20);
        }
        g.dispose();
    }
}
