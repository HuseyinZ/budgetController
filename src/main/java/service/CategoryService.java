package service;

import dao.CategoryDAO;
import dao.jdbc.CategoryJdbcDAO;
import model.Category;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class CategoryService {
    private final CategoryDAO categoryDAO;

    public CategoryService() {
        this(new CategoryJdbcDAO());
    }

    public CategoryService(CategoryDAO categoryDAO) {
        this.categoryDAO = Objects.requireNonNull(categoryDAO, "categoryDAO");
    }

    public List<Category> getAllCategories() {
        return categoryDAO.findAll(0, Integer.MAX_VALUE);
    }

    public Category getCategoryById(Long categoryId) {
        Optional<Category> opt = categoryDAO.findById(categoryId);
        return opt.orElse(null);
    }

    public Long createCategory(Category category) {
        return categoryDAO.create(category);
    }

    public void updateCategory(Category category) {
        categoryDAO.update(category);
    }

    public void deleteCategory(Long categoryId) {
        categoryDAO.deleteById(categoryId);
    }
}
