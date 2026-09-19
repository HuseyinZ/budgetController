package service;

import dao.ProductUnitDAO;
import model.ProductUnit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Birim servisi — DB tek kaynak, gömülü yedek liste YOK. */
class ProductUnitServiceTest {

    private static ProductUnit unit(String code, int order, boolean active) {
        ProductUnit u = new ProductUnit();
        u.setCode(code);
        u.setDisplayName(code);
        u.setDisplayOrder(order);
        u.setActive(active);
        return u;
    }

    @Test
    void returnsWhatTheDaoProvidesInOrder() {
        ProductUnitDAO dao = () -> List.of(unit("porsiyon", 1, true), unit("şiş", 2, true));
        List<ProductUnit> units = new ProductUnitService(dao).getActiveUnits();

        assertEquals(2, units.size());
        assertEquals("porsiyon", units.get(0).getCode());
        assertEquals("şiş", units.get(1).getCode());
    }

    @Test
    void resultIsImmutableSnapshot() {
        List<ProductUnit> backing = new ArrayList<>(List.of(unit("kg", 4, true)));
        List<ProductUnit> units = new ProductUnitService(() -> backing).getActiveUnits();

        assertThrows(UnsupportedOperationException.class, () -> units.add(unit("x", 9, true)));
        backing.clear();
        assertEquals(1, units.size(), "servis kendi kopyasını dönmeli");
    }

    @Test
    void databaseFailureIsVisibleAndHasNoHardcodedFallback() {
        ProductUnitDAO failing = () -> {
            throw new RuntimeException(new java.sql.SQLException("down", "08S01", 0));
        };
        ProductUnitService service = new ProductUnitService(failing);

        RuntimeException ex = assertThrows(RuntimeException.class, service::getActiveUnits);
        assertTrue(ex.getCause() instanceof java.sql.SQLException,
                "hata yutulmamalı, yüzeye çıkmalı");
    }

    @Test
    void emptyDatabaseYieldsEmptyListNotDefaults() {
        List<ProductUnit> units = new ProductUnitService(List::of).getActiveUnits();
        assertTrue(units.isEmpty(), "DB boşsa koddan birim uydurulmamalı");
    }
}
