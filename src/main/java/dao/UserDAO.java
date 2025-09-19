package dao;

import model.Role;
import model.User;

import java.util.Optional;

public interface UserDAO extends CrudRepository<User, Long> {
    Optional<User> findByUsername(String username);
    void updateRole(Long userId, Role role);
    long countByRole(Role role);
}
