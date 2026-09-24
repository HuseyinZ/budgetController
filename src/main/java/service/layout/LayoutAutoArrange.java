package service.layout;

import model.TableLayoutEntry;

import java.util.List;

/**
 * Koordinatı olmayan masalar için GEÇİCİ ızgara yerleşimi.
 *
 * <p>Yalnız GÖRÜNTÜ içindir: editörün açılması veritabanına hiçbir şey yazmaz.
 * Kullanıcı masayı sürüklerse o masa "kirli" sayılır ve ancak Kaydet ile
 * kalıcılaşır. Hesaplama saf fonksiyondur, DB'ye dokunmaz.
 */
public final class LayoutAutoArrange {

    /** Varsayılan görsel masa ölçüsü (0-1000 normalize uzayda). */
    public static final int DEFAULT_WIDTH = 110;
    public static final int DEFAULT_HEIGHT = 90;
    private static final int GAP = 20;
    private static final int COLUMNS = 7;

    private LayoutAutoArrange() {
    }

    /**
     * Yerleştirilmemiş masalara ızgara konumu üretir; zaten konumlu olanlara
     * DOKUNMAZ. Verilen nesneler kopyalanmaz — çağıran görüntüleme kopyasını
     * geçmelidir.
     *
     * @param tables alanın masaları (DB sırasında)
     */
    public static void arrangeMissing(List<TableLayoutEntry> tables) {
        if (tables == null) {
            return;
        }
        int slot = 0;
        for (TableLayoutEntry t : tables) {
            if (LayoutPlacementRules.isPlaced(t)) {
                continue;
            }
            int column = slot % COLUMNS;
            int row = slot / COLUMNS;
            int x = GAP + column * (DEFAULT_WIDTH + GAP);
            int y = GAP + row * (DEFAULT_HEIGHT + GAP);
            // Taşarsa son satıra sıkıştır: doğrulama sınırları içinde kal
            x = Math.min(x, LayoutPlacementRules.MAX_COORD - DEFAULT_WIDTH);
            y = Math.min(y, LayoutPlacementRules.MAX_COORD - DEFAULT_HEIGHT);
            t.setPosX(x);
            t.setPosY(y);
            t.setWidth(DEFAULT_WIDTH);
            t.setHeight(DEFAULT_HEIGHT);
            if (t.getShape() == null || t.getShape().isBlank()) {
                t.setShape(LayoutPlacementRules.DEFAULT_SHAPE);
            }
            slot++;
        }
    }
}
