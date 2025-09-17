package model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class ProductTest {

    @Test
    void stockCannotBeNegative() {
        Product product = new Product();
        assertThrows(IllegalArgumentException.class, () -> product.setStock(-1));
    }

    @Test
    void adjustStockPreventsNegative() {
        Product product = new Product();
        product.setStock(5);
        assertThrows(IllegalArgumentException.class, () -> product.adjustStock(-6));
    }

    @Test
    void nameAliasMethodsWork() {
        Product product = new Product();
        product.setName("Espresso");
        assertEquals("Espresso", product.getPName());
        product.setPName("Latte");
        assertEquals("Latte", product.getName());
    }

    @Test
    void unitPriceRoundedToTwoDecimals() {
        Product product = new Product();
        product.setUnitPrice(new BigDecimal("4.567"));
        assertEquals(new BigDecimal("4.57"), product.getUnitPrice());
    }
}
