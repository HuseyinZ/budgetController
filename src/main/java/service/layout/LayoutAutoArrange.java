package service.layout;

import model.TableLayoutEntry;
import model.TablePlacement;

import java.util.ArrayList;
import java.util.List;

/**
 * Koordinatı olmayan masalar için GEÇİCİ, DETERMİNİSTİK ızgara yerleşimi.
 *
 * <p>Yalnız GÖRÜNTÜ içindir: hiçbir şey veritabanına yazılmaz. Hem yönetim
 * editörü ({@link #arrangeMissing}) hem de "Katlar" görünümü
 * ({@link #resolve}) aynı algoritmayı kullanır, böylece iki ekran konumsuz
 * masayı aynı yerde gösterir.
 *
 * <p><b>Kaydedilmiş masalarla çakışmaz:</b> geçici yuvalar, konumu kayıtlı
 * masaların (ve daha önce atanmış geçici yuvaların) üzerine asla düşmez.
 * Yer kalmazsa daha küçük karo boyutuna geçilir. Kenar teması serbesttir
 * ({@link LayoutPlacementRules#findOverlap} ile aynı semantik).
 *
 * <p>Aynı girdi her zaman aynı çıktıyı üretir: sıra girdinin sırasıdır,
 * yuvalar satır satır soldan sağa taranır.
 */
public final class LayoutAutoArrange {

    /** Varsayılan görsel masa ölçüsü (0-1000 normalize uzayda). */
    public static final int DEFAULT_WIDTH = 110;
    public static final int DEFAULT_HEIGHT = 90;
    private static final int GAP = 20;

    /** Yer kalmazsa sırayla denenen karo ölçüleri — ilki varsayılandır. */
    private static final int[][] TILE_SIZES = {
            {DEFAULT_WIDTH, DEFAULT_HEIGHT},
            {80, 65},
            {55, 45},
            {35, 30},
    };

    private LayoutAutoArrange() {
    }

    /** Normalize uzayda eksenle hizalı dikdörtgen. */
    private record Box(int x, int y, int w, int h) {
        boolean intersects(Box o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }
    }

    // ------------------------------------------------------------------
    //  Editör: değiştirilebilir çalışma kopyası üzerinde
    // ------------------------------------------------------------------

    /**
     * Yerleştirilmemiş masalara geçici konum üretir; konumlu olanlara
     * DOKUNMAZ. Nesneler yerinde güncellenir — çağıran görüntüleme kopyasını
     * geçmelidir.
     */
    public static void arrangeMissing(List<TableLayoutEntry> tables) {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        List<Box> occupied = new ArrayList<>();
        int missing = 0;
        for (TableLayoutEntry t : tables) {
            if (LayoutPlacementRules.isPlaced(t)) {
                occupied.add(new Box(t.getPosX(), t.getPosY(), t.getWidth(), t.getHeight()));
            } else {
                missing++;
            }
        }
        if (missing == 0) {
            return;
        }
        List<Box> slots = assignSlots(occupied, missing);
        int i = 0;
        for (TableLayoutEntry t : tables) {
            if (LayoutPlacementRules.isPlaced(t)) {
                continue;
            }
            Box b = slots.get(i++);
            t.setPosX(b.x());
            t.setPosY(b.y());
            t.setWidth(b.w());
            t.setHeight(b.h());
            if (t.getShape() == null || t.getShape().isBlank()) {
                t.setShape(LayoutPlacementRules.DEFAULT_SHAPE);
            }
        }
    }

    // ------------------------------------------------------------------
    //  "Katlar" görünümü: değişmez yerleşimler üzerinde
    // ------------------------------------------------------------------

    /**
     * Değişmez yerleşimleri görüntülenebilir hale getirir: konumlu olanlar
     * aynen döner, konumsuzlar geçici yuvayla doldurulur. Girdi değiştirilmez;
     * sıra korunur.
     */
    public static List<TablePlacement> resolve(List<TablePlacement> placements) {
        if (placements == null || placements.isEmpty()) {
            return List.of();
        }
        List<Box> occupied = new ArrayList<>();
        int missing = 0;
        for (TablePlacement p : placements) {
            if (p.isPlaced()) {
                occupied.add(new Box(p.posX(), p.posY(), p.width(), p.height()));
            } else {
                missing++;
            }
        }
        List<Box> slots = missing == 0 ? List.of() : assignSlots(occupied, missing);
        List<TablePlacement> out = new ArrayList<>(placements.size());
        int i = 0;
        for (TablePlacement p : placements) {
            if (p.isPlaced()) {
                out.add(p);
            } else {
                Box b = slots.get(i++);
                out.add(p.withRect(b.x(), b.y(), b.w(), b.h()));
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------

    /**
     * {@code count} adet boş yuva üretir. Her yuva; kayıtlı masalarla ve daha
     * önce verilen yuvalarla çakışmaz. {@code occupied} listesi büyütülür.
     */
    private static List<Box> assignSlots(List<Box> occupied, int count) {
        List<Box> out = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            Box slot = firstFreeSlot(occupied);
            occupied.add(slot);
            out.add(slot);
        }
        return out;
    }

    private static Box firstFreeSlot(List<Box> occupied) {
        int max = LayoutPlacementRules.MAX_COORD;
        for (int[] size : TILE_SIZES) {
            int w = size[0];
            int h = size[1];
            for (int y = GAP; y + h <= max; y += h + GAP) {
                for (int x = GAP; x + w <= max; x += w + GAP) {
                    Box candidate = new Box(x, y, w, h);
                    if (occupied.stream().noneMatch(candidate::intersects)) {
                        return candidate;
                    }
                }
            }
        }
        // Normalize uzay kayıtlı masalarla tamamen doluysa çakışmasız yer
        // kalmamıştır; en küçük karo sol üste konur. Gerçekçi düzenlerde
        // (en küçük karoyla ~500 yuva) bu dala ulaşılmaz.
        int[] smallest = TILE_SIZES[TILE_SIZES.length - 1];
        return new Box(0, 0, smallest[0], smallest[1]);
    }
}
