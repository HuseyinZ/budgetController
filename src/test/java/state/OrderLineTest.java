package state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 1A — {@link OrderLine} itemId propagation focused testleri.
 *
 * <p>Saf POJO + mevcut Jackson bağımlılığı; server/harness gerektirmez.
 */
class OrderLineTest {

    @Test
    void fullConstructorPreservesItemId() {
        OrderLine line = new OrderLine("Adana Kebap", new BigDecimal("250.00"),
                2, true, "Soğansız", 101L);
        assertEquals(101L, line.getItemId(), "6-arg constructor must preserve itemId");
        // Mevcut alan davranışları değişmedi
        assertEquals("Adana Kebap", line.getProductName());
        assertEquals(2, line.getQuantity());
        assertEquals("Soğansız", line.getNote());
        assertTrue(line.isPending());
    }

    @Test
    void legacyConstructorsLeaveItemIdNull() {
        assertNull(new OrderLine("Adana", new BigDecimal("250.00"), 1).getItemId(),
                "3-arg legacy constructor must leave itemId null");
        assertNull(new OrderLine("Adana", new BigDecimal("250.00"), 1, false).getItemId(),
                "4-arg legacy constructor must leave itemId null");
        assertNull(new OrderLine("Adana", new BigDecimal("250.00"), 1, false, "Acılı").getItemId(),
                "5-arg legacy constructor must leave itemId null");
    }

    @Test
    void jacksonSerializesItemIdPropertyName() throws Exception {
        // PWA kontratı: snapshot JSON'ında alan adı "itemId" olmalı ve id değerini taşımalı.
        OrderLine line = new OrderLine("Adana Kebap", new BigDecimal("250.00"),
                2, true, "Soğansız", 101L);
        JsonNode json = new ObjectMapper().valueToTree(line);
        assertTrue(json.has("itemId"), "JSON must expose the 'itemId' property");
        assertEquals(101L, json.get("itemId").asLong(), "'itemId' must carry order_items.id");
        // productId kavramıyla karışmadığının teyidi: OrderLine productId üretmez.
        assertTrue(!json.has("productId"), "OrderLine JSON must not invent a productId field");
    }

    @Test
    void jacksonSerializesNullItemIdForLegacyLines() throws Exception {
        OrderLine line = new OrderLine("Adana", new BigDecimal("250.00"), 1);
        JsonNode json = new ObjectMapper().valueToTree(line);
        assertTrue(json.has("itemId"), "null itemId must still appear (no NON_NULL config)");
        assertTrue(json.get("itemId").isNull(), "legacy line must serialize itemId as null");
    }

    // ------------------------------------------------------------------
    //   F1/F2 — quantityLabel formatting
    // ------------------------------------------------------------------

    private static OrderLine pieceLine(int quantity, Integer piecesPerPortion, String unitLabel) {
        return new OrderLine("Adana Kebap", new BigDecimal("41.67"), quantity,
                true, null, 101L, piecesPerPortion, unitLabel);
    }

    @Test
    void quantityLabelWholePortions() {
        assertEquals("6 şiş (3 porsiyon)", pieceLine(6, 2, "şiş").getQuantityLabel());
    }

    @Test
    void quantityLabelHalfPortionUsesTurkishComma() {
        assertEquals("3 şiş (1,5 porsiyon)", pieceLine(3, 2, "şiş").getQuantityLabel());
    }

    @Test
    void quantityLabelRoundsToTwoDecimalsHalfUp() {
        // 1/3 = 0,333... → HALF_UP 2 ondalık → 0,33
        assertEquals("1 şiş (0,33 porsiyon)", pieceLine(1, 3, "şiş").getQuantityLabel());
    }

    @Test
    void quantityLabelPortionBasedWithUnitLabel() {
        assertEquals("2 porsiyon", pieceLine(2, null, "porsiyon").getQuantityLabel());
    }

    @Test
    void quantityLabelPlainWhenNoUnitInfo() {
        assertEquals("2", pieceLine(2, null, null).getQuantityLabel());
    }

    @Test
    void quantityLabelBlankUnitLabelFallsBackToSis() {
        assertEquals("2 şiş (1 porsiyon)", pieceLine(2, 2, "   ").getQuantityLabel());
    }

    @Test
    void jacksonSerializesQuantityLabelProperty() throws Exception {
        JsonNode json = new ObjectMapper().valueToTree(pieceLine(6, 2, "şiş"));
        assertTrue(json.has("quantityLabel"), "JSON must expose 'quantityLabel' for the PWA");
        assertEquals("6 şiş (3 porsiyon)", json.get("quantityLabel").asText());
    }
}
