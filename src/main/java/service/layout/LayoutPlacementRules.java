package service.layout;

import model.TableLayoutEntry;

import java.util.Locale;
import java.util.Set;

/**
 * Görsel yerleşim doğrulaması. Koordinatlar 0-1000 normalize uzayındadır;
 * gerçek piksel dönüşümü ileride editörün işidir.
 *
 * <p>Amaç: editör henüz yokken bile DB'ye çizilemeyecek değer girmemek.
 */
public final class LayoutPlacementRules {

    /** Normalize uzayın üst sınırı (0..1000). */
    public static final int MAX_COORD = 1000;
    public static final int MIN_SIZE = 1;
    public static final int MAX_ROTATION = 359;

    /** Desteklenen şekiller — editör bunların dışını çizemez. */
    public static final Set<String> SUPPORTED_SHAPES = Set.of("RECT", "SQUARE", "ROUND", "OVAL");

    public static final String DEFAULT_SHAPE = "RECT";

    private LayoutPlacementRules() {
    }

    /** Geçersiz yerleşim — servis bunu yakalayıp işlemi reddeder. */
    public static class InvalidPlacementException extends IllegalArgumentException {
        public InvalidPlacementException(String message) {
            super(message);
        }
    }

    /**
     * Yerleşimi doğrular ve normalize eder (şekil büyük harfe çevrilir,
     * boş şekil {@link #DEFAULT_SHAPE} olur).
     *
     * <p>Koordinat/ölçü alanları hep birlikte verilmeli veya hep birlikte
     * boş bırakılmalıdır: yarım yerleşim ileride çizilemez.
     *
     * @throws InvalidPlacementException değerler normalize uzayın dışındaysa
     */
    public static void validateAndNormalize(TableLayoutEntry t) {
        if (t == null) {
            throw new InvalidPlacementException("Yerleşim boş olamaz");
        }
        if (t.getTableNo() <= 0) {
            throw new InvalidPlacementException("Masa numarası pozitif olmalı: " + t.getTableNo());
        }
        if (t.getDisplayOrder() < 0) {
            throw new InvalidPlacementException("Sıra negatif olamaz: " + t.getDisplayOrder());
        }

        String shape = t.getShape() == null || t.getShape().isBlank()
                ? DEFAULT_SHAPE
                : t.getShape().trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_SHAPES.contains(shape)) {
            throw new InvalidPlacementException("Desteklenmeyen şekil: " + shape
                    + " (izin verilenler: " + SUPPORTED_SHAPES + ")");
        }
        t.setShape(shape);

        int rotation = t.getRotationDeg();
        if (rotation < 0 || rotation > MAX_ROTATION) {
            throw new InvalidPlacementException(
                    "Dönüş açısı 0-" + MAX_ROTATION + " aralığında olmalı: " + rotation);
        }

        boolean hasPosition = t.getPosX() != null || t.getPosY() != null;
        boolean hasSize = t.getWidth() != null || t.getHeight() != null;

        if (!hasPosition && !hasSize) {
            return;   // yerleştirilmemiş masa — editör sonra konumlandırır
        }
        if (t.getPosX() == null || t.getPosY() == null) {
            throw new InvalidPlacementException("Konum eksik: pos_x ve pos_y birlikte verilmeli");
        }
        if (t.getWidth() == null || t.getHeight() == null) {
            throw new InvalidPlacementException("Ölçü eksik: width ve height birlikte verilmeli");
        }

        requireRange("pos_x", t.getPosX(), 0, MAX_COORD);
        requireRange("pos_y", t.getPosY(), 0, MAX_COORD);
        requireRange("width", t.getWidth(), MIN_SIZE, MAX_COORD);
        requireRange("height", t.getHeight(), MIN_SIZE, MAX_COORD);

        if (t.getPosX() + t.getWidth() > MAX_COORD) {
            throw new InvalidPlacementException("Masa sağ kenardan taşıyor: pos_x + width > " + MAX_COORD);
        }
        if (t.getPosY() + t.getHeight() > MAX_COORD) {
            throw new InvalidPlacementException("Masa alt kenardan taşıyor: pos_y + height > " + MAX_COORD);
        }
    }

    /** Yerleştirilmiş (koordinatı olan) masa mı? */
    public static boolean isPlaced(TableLayoutEntry t) {
        return t != null && t.getPosX() != null && t.getPosY() != null
                && t.getWidth() != null && t.getHeight() != null;
    }

    /**
     * Aynı alandaki masaların üst üste binip binmediğini kontrol eder.
     * Yalnız yerleştirilmiş masalar karşılaştırılır; kenar teması çakışma
     * sayılmaz (yan yana masa serbesttir).
     *
     * @return çakışan ilk masa çiftini anlatan mesaj; çakışma yoksa boş
     */
    public static java.util.Optional<String> findOverlap(java.util.List<TableLayoutEntry> tables) {
        java.util.List<TableLayoutEntry> placed = tables.stream()
                .filter(LayoutPlacementRules::isPlaced)
                .toList();
        for (int i = 0; i < placed.size(); i++) {
            for (int j = i + 1; j < placed.size(); j++) {
                TableLayoutEntry a = placed.get(i);
                TableLayoutEntry b = placed.get(j);
                if (intersects(a, b)) {
                    return java.util.Optional.of(
                            "Masa " + a.getTableNo() + " ile masa " + b.getTableNo() + " üst üste biniyor");
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean intersects(TableLayoutEntry a, TableLayoutEntry b) {
        int ax2 = a.getPosX() + a.getWidth();
        int ay2 = a.getPosY() + a.getHeight();
        int bx2 = b.getPosX() + b.getWidth();
        int by2 = b.getPosY() + b.getHeight();
        return a.getPosX() < bx2 && b.getPosX() < ax2
                && a.getPosY() < by2 && b.getPosY() < ay2;
    }

    private static void requireRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new InvalidPlacementException(
                    field + " " + min + "-" + max + " aralığında olmalı: " + value);
        }
    }
}
