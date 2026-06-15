package service.print;

import dao.CategoryPrinterRouteDAO;
import dao.KitchenPrinterDAO;
import dao.ProductDAO;
import model.CategoryPrinterRoute;
import model.KitchenPrinter;
import model.OrderItem;
import model.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class KitchenRouterTest {

    @Test
    void categori_to_printer_routing_separates_items_correctly() {
        // İki kategori: 10=Ciğer, 20=Döner. İki yazıcı: 1=Mutfak1, 2=Mutfak2
        Product liver = newProduct(101L, "Kuzu Ciğer", 10L);
        Product doner = newProduct(102L, "Pide Döner", 20L);

        FakeProductDAO products = new FakeProductDAO(Map.of(101L, liver, 102L, doner));
        FakeKitchenPrinterDAO printers = new FakeKitchenPrinterDAO(Map.of(
                1, newPrinter(1, "K1"),
                2, newPrinter(2, "K2")
        ));
        FakeRouteDAO routes = new FakeRouteDAO(Map.of(
                10L, List.of(1),
                20L, List.of(2)
        ));

        KitchenRouter router = new KitchenRouter(printers, routes, products);

        OrderItem i1 = newItem(101L, 2, "Kuzu Ciğer");
        OrderItem i2 = newItem(102L, 1, "Pide Döner");
        OrderItem i3 = newItem(101L, 1, "Kuzu Ciğer");

        Map<KitchenPrinter, List<OrderItem>> grouped =
                router.routeItems(List.of(i1, i2, i3));

        assertEquals(2, grouped.size());

        KitchenPrinter k1 = printers.findById(1).orElseThrow();
        KitchenPrinter k2 = printers.findById(2).orElseThrow();

        assertEquals(2, grouped.get(k1).size(), "K1'e ciğer kalemleri düşmeli");
        assertEquals(1, grouped.get(k2).size(), "K2'ye döner kalemleri düşmeli");
    }

    @Test
    void category_with_two_printers_goes_to_both() {
        Product liver = newProduct(101L, "Ciğer", 10L);
        FakeProductDAO products = new FakeProductDAO(Map.of(101L, liver));
        FakeKitchenPrinterDAO printers = new FakeKitchenPrinterDAO(Map.of(
                1, newPrinter(1, "K1"),
                3, newPrinter(3, "K3")
        ));
        FakeRouteDAO routes = new FakeRouteDAO(Map.of(
                10L, List.of(1, 3)   // aynı kategori iki yazıcıya
        ));

        KitchenRouter router = new KitchenRouter(printers, routes, products);
        Map<KitchenPrinter, List<OrderItem>> grouped =
                router.routeItems(List.of(newItem(101L, 1, "Ciğer")));

        assertEquals(2, grouped.size(), "Her iki yazıcıya da düşmeli");
    }

    @Test
    void item_without_route_is_skipped() {
        Product orphan = newProduct(999L, "Atanmamış Ürün", 50L);
        FakeProductDAO products = new FakeProductDAO(Map.of(999L, orphan));
        FakeKitchenPrinterDAO printers = new FakeKitchenPrinterDAO(Collections.emptyMap());
        FakeRouteDAO routes = new FakeRouteDAO(Collections.emptyMap());

        KitchenRouter router = new KitchenRouter(printers, routes, products);
        Map<KitchenPrinter, List<OrderItem>> grouped =
                router.routeItems(List.of(newItem(999L, 1, "Atanmamış Ürün")));

        assertTrue(grouped.isEmpty(), "Yönlendirme yoksa boş dönmeli");
    }

    @Test
    void inactive_printer_is_skipped() {
        Product liver = newProduct(101L, "Ciğer", 10L);
        FakeProductDAO products = new FakeProductDAO(Map.of(101L, liver));
        KitchenPrinter inactive = newPrinter(1, "K1");
        inactive.setActive(false);
        FakeKitchenPrinterDAO printers = new FakeKitchenPrinterDAO(Map.of(1, inactive));
        FakeRouteDAO routes = new FakeRouteDAO(Map.of(10L, List.of(1)));

        KitchenRouter router = new KitchenRouter(printers, routes, products);
        Map<KitchenPrinter, List<OrderItem>> grouped =
                router.routeItems(List.of(newItem(101L, 1, "Ciğer")));

        assertTrue(grouped.isEmpty(), "Pasif yazıcı atlanmalı");
    }

    // ---- yardımcılar ----

    private static OrderItem newItem(Long productId, int qty, String name) {
        OrderItem i = new OrderItem();
        i.setProductId(productId);
        i.setProductName(name);
        i.setQuantity(qty);
        i.setUnitPrice(new BigDecimal("10.00"));
        return i;
    }

    private static Product newProduct(Long id, String name, Long categoryId) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setCategoryId(categoryId);
        p.setUnitPrice(new BigDecimal("10.00"));
        return p;
    }

    private static KitchenPrinter newPrinter(int id, String code) {
        KitchenPrinter p = new KitchenPrinter(code, code, "127.0.0.1");
        p.setId((long) id);
        return p;
    }

    // ---- fakes ----

    static class FakeKitchenPrinterDAO implements KitchenPrinterDAO {
        private final Map<Integer, KitchenPrinter> store;
        FakeKitchenPrinterDAO(Map<Integer, KitchenPrinter> store) { this.store = store; }
        @Override public Integer create(KitchenPrinter e)        { throw new UnsupportedOperationException(); }
        @Override public void update(KitchenPrinter e)           { store.put(e.getId().intValue(), e); }
        @Override public void deleteById(Integer id)             { store.remove(id); }
        @Override public Optional<KitchenPrinter> findById(Integer id) { return Optional.ofNullable(store.get(id)); }
        @Override public List<KitchenPrinter> findAll(int o, int l)    { return new ArrayList<>(store.values()); }
        @Override public Optional<KitchenPrinter> findByCode(String c) {
            return store.values().stream().filter(p -> c.equals(p.getCode())).findFirst();
        }
        @Override public List<KitchenPrinter> findActive() {
            return store.values().stream().filter(KitchenPrinter::isActive).toList();
        }
    }

    static class FakeRouteDAO implements CategoryPrinterRouteDAO {
        private final Map<Long, List<Integer>> map;
        FakeRouteDAO(Map<Long, List<Integer>> map) { this.map = map; }
        @Override public List<Integer> findPrinterIdsByCategory(Long categoryId) {
            return map.getOrDefault(categoryId, List.of());
        }
        @Override public List<CategoryPrinterRoute> findAll()                  { return List.of(); }
        @Override public void link(Long c, Integer p)                          { }
        @Override public void unlink(Long c, Integer p)                        { }
        @Override public void deleteByCategory(Long categoryId)                { }
    }

    static class FakeProductDAO implements ProductDAO {
        private final Map<Long, Product> store;
        FakeProductDAO(Map<Long, Product> store) { this.store = store; }
        @Override public Long create(Product e)                  { throw new UnsupportedOperationException(); }
        @Override public void update(Product e)                  { store.put(e.getId(), e); }
        @Override public void deleteById(Long id)                { store.remove(id); }
        @Override public Optional<Product> findById(Long id)     { return Optional.ofNullable(store.get(id)); }
        @Override public List<Product> findAll(int o, int l)     { return new ArrayList<>(store.values()); }
        @Override public List<Product> searchByName(String q, int l)            { return List.of(); }
        @Override public List<Product> findByCategory(Long c, int o, int l)     { return List.of(); }
        @Override public List<Product> findByCategoryName(String n)             { return List.of(); }
        @Override public void updateStock(Long productId, int delta)            { }
        @Override public Optional<Product> findByName(String name)              { return Optional.empty(); }
    }
}
