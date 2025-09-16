package dao;

import java.util.List;
import java.util.Optional;

public interface CrudRepository<T, ID> {

    ID create(T e);

    void update(T e);

    void deleteById(ID id);

    Optional<T> findById(ID id);

    List<T> findAll(int offset, int limit);
}
