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
}
