package dao;


import model.*;

import java.util.List;


public interface CategoryDAO extends CrudRepository<Category, Long> {
    List<Category> searchByName(String q, int limit);
}


