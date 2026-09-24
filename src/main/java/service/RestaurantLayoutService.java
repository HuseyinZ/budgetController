package service;

import dao.RestaurantLayoutDAO;
import dao.jdbc.RestaurantLayoutJdbcDAO;
import model.RestaurantArea;
import model.TableLayoutEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Masa düzeninin TEK runtime kaynağı — {@code restaurant_areas} +
 * {@code restaurant_table_layout} (V004).
 *
 * <p>{@code restaurant-layout.properties} artık runtime'da OKUNMAZ ve gömülü
 * varsayılan düzen YOKTUR. Düzen okunamaz veya tutarsızsa
 * {@link LayoutUnavailableException} fırlatılır: yanlış/eksik bir masa
 * düzeniyle çalışmak siparişin yanlış masaya yazılmasına yol açabilir, bu
 * yüzden sessiz yedeğe düşmek bilinçli olarak reddedilir.
 *
 * <p>Yalnız okuma yapar; hiçbir koşulda alan/masa oluşturmaz.
 */
public class RestaurantLayoutService {

    /**
     * Bir alan, ona bağlı masa numaraları ve bu masaların DEĞİŞMEZ yerleşimleri
     * (DB sırasında). {@code placements} ile {@code tableNumbers} aynı sıradadır.
     */
    public record AreaTables(RestaurantArea area,
                             List<Integer> tableNumbers,
                             List<model.TablePlacement> placements) {
        public AreaTables {
            tableNumbers = List.copyOf(tableNumbers);
            placements = placements == null ? List.of() : List.copyOf(placements);
        }

        /** Yerleşim bilgisi olmadan (tüm masalar konumsuz kabul edilir). */
        public AreaTables(RestaurantArea area, List<Integer> tableNumbers) {
            this(area, tableNumbers, List.of());
        }
    }

    /** Düzen okunamadı veya tutarsız — uygulama sessizce devam etmemeli. */
    public static class LayoutUnavailableException extends IllegalStateException {
        public LayoutUnavailableException(String message) {
            super(message);
        }
    }

    private final RestaurantLayoutDAO dao;

    public RestaurantLayoutService() {
        this(new RestaurantLayoutJdbcDAO());
    }

    public RestaurantLayoutService(RestaurantLayoutDAO dao) {
        this.dao = dao;
    }

    /**
     * Aktif düzeni okur ve doğrular.
     *
     * <p>Sıra deterministiktir: alanlar {@code display_order} → {@code id},
     * masalar alan içinde {@code display_order} → {@code table_no}.
     * Masa numaraları bitişik olmak zorunda DEĞİLDİR.
     *
     * @throws LayoutUnavailableException aktif alan yoksa, aktif masa yoksa,
     *         bir masa bilinmeyen/pasif bir alana bağlıysa, masa numarası
     *         geçersizse veya aynı numara birden çok kez geçiyorsa
     */
    public List<AreaTables> loadActiveLayout() {
        List<RestaurantArea> areas;
        List<TableLayoutEntry> tables;
        try {
            areas = dao.findActiveAreasOrdered();
            tables = dao.findActiveTablesOrdered();
        } catch (RuntimeException ex) {
            throw new LayoutUnavailableException(
                    "Masa düzeni veritabanından okunamadı (" + ex.getClass().getSimpleName() + ")");
        }

        if (areas == null || areas.isEmpty()) {
            throw new LayoutUnavailableException(
                    "Masa düzeni boş: restaurant_areas tablosunda aktif alan yok");
        }
        if (tables == null || tables.isEmpty()) {
            throw new LayoutUnavailableException(
                    "Masa düzeni boş: restaurant_table_layout tablosunda aktif masa yok");
        }

        Map<Integer, List<Integer>> byArea = new LinkedHashMap<>();
        // Yerleşim alanları (x/y/genişlik/yükseklik/şekil/dönüş) çalışan düzene
        // taşınır; aksi halde "Katlar" görünümü kaydedilen yerleşimi göremez.
        Map<Integer, List<model.TablePlacement>> placementsByArea = new LinkedHashMap<>();
        Map<Integer, RestaurantArea> areaById = new LinkedHashMap<>();
        for (RestaurantArea area : areas) {
            if (area.getId() == null) {
                throw new LayoutUnavailableException("Masa düzeni geçersiz: alan kimliği boş");
            }
            if (areaById.put(area.getId(), area) != null) {
                throw new LayoutUnavailableException(
                        "Masa düzeni geçersiz: alan kimliği yinelenmiş (" + area.getId() + ")");
            }
            byArea.put(area.getId(), new ArrayList<>());
            placementsByArea.put(area.getId(), new ArrayList<>());
        }

        Set<Integer> seenTables = new LinkedHashSet<>();
        for (TableLayoutEntry t : tables) {
            if (t.getTableNo() <= 0) {
                throw new LayoutUnavailableException(
                        "Masa düzeni geçersiz: masa numarası pozitif olmalı (" + t.getTableNo() + ")");
            }
            if (!seenTables.add(t.getTableNo())) {
                throw new LayoutUnavailableException(
                        "Masa düzeni geçersiz: masa numarası yinelenmiş (" + t.getTableNo() + ")");
            }
            List<Integer> bucket = byArea.get(t.getAreaId());
            if (bucket == null) {
                // Pasif alana bağlı ya da hiç bulunmayan alan → sessizce atlanmaz.
                throw new LayoutUnavailableException(
                        "Masa düzeni geçersiz: masa " + t.getTableNo()
                                + " aktif olmayan veya bilinmeyen bir alana bağlı");
            }
            bucket.add(t.getTableNo());
            placementsByArea.get(t.getAreaId()).add(model.TablePlacement.of(t));
        }

        List<AreaTables> out = new ArrayList<>();
        for (RestaurantArea area : areas) {
            List<Integer> numbers = byArea.get(area.getId());
            if (numbers.isEmpty()) {
                // Masasız alan UI'da boş sekme üretir; tutarsızlık olarak raporlanır.
                throw new LayoutUnavailableException(
                        "Masa düzeni geçersiz: alanda hiç aktif masa yok ("
                                + area.getBuilding() + " / " + area.getFloor() + ")");
            }
            out.add(new AreaTables(area, numbers, placementsByArea.get(area.getId())));
        }
        return List.copyOf(out);
    }
}
