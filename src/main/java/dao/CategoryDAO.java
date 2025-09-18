package dao;


import model.Category;

import java.util.List;
import java.util.Optional;


public interface CategoryDAO extends CrudRepository<Category, Long> {
    List<Category> searchByName(String q, int limit);

    Optional<Category> findByName(String name);
}


