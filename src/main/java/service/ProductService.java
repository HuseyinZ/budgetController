package service;

import dao.ProductDAO;
import dao.jdbc.ProductJdbcDAO;
import model.Product;

import java.util.List;
import java.util.Objects;

public class ProductService {
    private final ProductDAO productDAO;

    public ProductService() {
        this(new ProductJdbcDAO());
    }

    public ProductService(ProductDAO productDAO) {
        this.productDAO = Objects.requireNonNull(productDAO, "productDAO");
    }

    public List<Product> getAllProducts() {
        return productDAO.findAll(0, Integer.MAX_VALUE);
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
}
