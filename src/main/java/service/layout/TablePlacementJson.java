package service.layout;

import model.TablePlacement;
import state.AppState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/tables} için masa yerleşim alanları.
 *
 * <p>Kaynak, {@code AppState} anlık görüntüsündeki {@link AppState.AreaDefinition}
 * yerleşimleridir — veritabanına erişilmez. Konumu olmayan masalar, Swing
 * "Katlar" görünümüyle AYNI deterministik geçici yerleşimi alır
 * ({@link LayoutAutoArrange#resolve}); hiçbir şey kaydedilmez.
 */
public final class TablePlacementJson {

    private TablePlacementJson() {
    }

    /** Alanın masaları için görüntülenebilir (konumu çözülmüş) yerleşimler. */
    public static Map<Integer, TablePlacement> resolvedByTableNo(AppState.AreaDefinition area) {
        Map<Integer, TablePlacement> out = new LinkedHashMap<>();
        for (TablePlacement p : LayoutAutoArrange.resolve(area.getPlacements())) {
            out.put(p.tableNo(), p);
        }
        return out;
    }

    /**
     * Masa satırına 0-1000 normalize yerleşim alanlarını ekler. Yerleşim yoksa
     * alan eklenmez; PWA bu durumda ızgara görünümünde kalır.
     */
    public static void putPlacement(Map<String, Object> row, TablePlacement p) {
        if (p == null || !p.isPlaced()) {
            return;
        }
        row.put("posX", p.posX());
        row.put("posY", p.posY());
        row.put("width", p.width());
        row.put("height", p.height());
        row.put("shape", p.shape());
        row.put("rotationDeg", p.rotationDeg());
    }
}
