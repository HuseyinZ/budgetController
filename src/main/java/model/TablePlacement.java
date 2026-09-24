package model;

import java.util.Locale;
import java.util.Set;

/**
 * DEĞİŞMEZ masa yerleşimi — 0-1000 normalize uzayda.
 *
 * <p>{@link TableLayoutEntry} değiştirilebilir bir DAO/editör nesnesidir; bu
 * record ise çalışan düzenin ({@code AppState.LayoutSnapshot}) içine konan
 * salt okunur kopyadır. Böylece atomik olarak takas edilen görüntü sonradan
 * değiştirilemez.
 *
 * <p>{@code posX/posY/width/height} boş olabilir: masa henüz
 * konumlandırılmamıştır ve görüntü tarafında geçici yerleşim alır.
 */
public record TablePlacement(int tableNo,
                             Integer posX,
                             Integer posY,
                             Integer width,
                             Integer height,
                             String shape,
                             int rotationDeg) {

    public static final String DEFAULT_SHAPE = "RECT";
    private static final Set<String> KNOWN_SHAPES = Set.of("RECT", "SQUARE", "ROUND", "OVAL");

    public TablePlacement {
        shape = normalizeShape(shape);
    }

    /** Konumu ve ölçüsü eksiksiz mi? */
    public boolean isPlaced() {
        return posX != null && posY != null && width != null && height != null;
    }

    /** Konumsuz yerleşim — yalnız numara ve varsayılan görünüm. */
    public static TablePlacement unplaced(int tableNo) {
        return new TablePlacement(tableNo, null, null, null, null, DEFAULT_SHAPE, 0);
    }

    /** Editör/DAO nesnesinden değişmez kopya. */
    public static TablePlacement of(TableLayoutEntry t) {
        return new TablePlacement(t.getTableNo(), t.getPosX(), t.getPosY(),
                t.getWidth(), t.getHeight(), t.getShape(), t.getRotationDeg());
    }

    /** Aynı masa, verilen dikdörtgenle (şekil ve dönüş korunur). */
    public TablePlacement withRect(int x, int y, int w, int h) {
        return new TablePlacement(tableNo, x, y, w, h, shape, rotationDeg);
    }

    /** Bilinmeyen/boş şekil güvenle dikdörtgene düşer; görüntü asla kırılmaz. */
    public static String normalizeShape(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SHAPE;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return KNOWN_SHAPES.contains(s) ? s : DEFAULT_SHAPE;
    }

    /** Yuvarlak çizilen şekiller. */
    public boolean isElliptical() {
        return "ROUND".equals(shape) || "OVAL".equals(shape);
    }
}
