package service.expense;

/**
 * Hızlı seçim için gider şablonu — bir kalemi tek tıkla eklemeye yarar.
 *
 * @param name        Gider başlığı (örn. "Domates")
 * @param icon        Emoji veya kısa metin (örn. "🍅")
 * @param defaultMode "kg" → kg + kg fiyatı sorulur; "manuel" → toplam tutar
 */
public record ExpenseTemplate(String name, String icon, String defaultMode) {

    public boolean isKgMode() {
        return defaultMode != null && defaultMode.equalsIgnoreCase("kg");
    }
}
