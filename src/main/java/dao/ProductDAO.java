package dao;

import model.Product;

import java.util.List;

public interface ProductDAO extends CrudRepository<Product, Long> {
    List<Product> searchByName(String q, int limit);
    List<Product> findByCategory(Long categoryId, int offset, int limit);
    void updateStock(Long productId, int delta); // +/-
}
