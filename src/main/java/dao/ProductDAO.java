package dao;

import model.Product;

import java.util.List;
import java.util.Optional;

public interface ProductDAO extends CrudRepository<Product, Long> {
    List<Product> searchByName(String q, int limit);
    List<Product> findByCategory(Long categoryId, int offset, int limit);
    List<Product> findByCategoryName(String categoryName);
    void updateStock(Long productId, int delta); // +/-
    Optional<Product> findByName(String name);
}
