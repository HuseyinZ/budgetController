package model;

/**
 * Stage 0G — not çakışması korumalı ürün ekleme sonucu.
 *
 * <ul>
 *   <li>{@code itemAdded == false} → not çakışması; ürün eklenmedi, {@code noteResult} null.</li>
 *   <li>{@code itemAdded == true && noteResult == null} → ürün eklendi; not istenmedi.</li>
 *   <li>{@code itemAdded == true && noteResult != null} → ürün eklendi; not sonucu {@code noteResult}.</li>
 * </ul>
 */
public record ItemAddWithNoteResult(
        boolean itemAdded,
        ItemNoteUpdateResult noteResult
) {
}
