package service;

import dao.ProductDAO;
import dao.jdbc.ProductJdbcDAO;
import model.Category;
import model.Product;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ProductService {
    private final ProductDAO productDAO;
    private final CategoryService categoryService;

    public ProductService() {
        this(new ProductJdbcDAO(), new CategoryService());
    }

    public ProductService(CategoryService categoryService) {
        this(new ProductJdbcDAO(), categoryService);
    }

    public ProductService(ProductDAO productDAO) {
        this(productDAO, new CategoryService());
    }

    public ProductService(ProductDAO productDAO, CategoryService categoryService) {
        this.productDAO = Objects.requireNonNull(productDAO, "productDAO");
        this.categoryService = Objects.requireNonNull(categoryService, "categoryService");
    }

    public List<Product> getAllProducts() {
        return productDAO.findAll(0, Integer.MAX_VALUE);
    }

    public List<Product> getProductsByCategory(Long categoryId, int offset, int limit) {
        if (categoryId == null) {
            return List.of();
        }
        return productDAO.findByCategory(categoryId, offset, limit);
    }

    public List<Product> getProductsByCategoryName(String categoryName) {
        if (categoryName == null) {
            return getAllProducts();
        }
        String trimmed = categoryName.trim();
        if (trimmed.isEmpty()) {
            return getAllProducts();
        }
        Optional<Category> category = categoryService.findByName(trimmed);
        if (category.isEmpty()) {
            return List.of();
        }
        return getProductsByCategory(category.get().getId(), 0, 1_000);
    }

    public Product getProductById(Long productId) {
        return productDAO.findById(productId).orElse(null);
    }

    public Long createProduct(Product product) {
        return productDAO.create(product);
    }

    public void updateProduct(Product product) {
        productDAO.update(product);
    }

    public void deleteProduct(Long productId) {
        productDAO.deleteById(productId);
    }

    public void increaseProductStock(Long productId, int amount, String note) {
        if (amount <= 0) throw new IllegalArgumentException("amount > 0 olmalı");
        productDAO.updateStock(productId, amount);
        // Not bilgisini kalıcı tutmak istiyorsan ayrı “procurements” tablosu önerilir.
    }

    public void decreaseProductStock(Long productId, int amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount > 0 olmalı");
        productDAO.updateStock(productId, -amount);
    }

    public Optional<Product> findByName(String name) {
        return productDAO.findByName(name);
    }
}
