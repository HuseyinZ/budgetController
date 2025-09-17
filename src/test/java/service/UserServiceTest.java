package service;

import dao.UserDAO;
import model.Role;
import model.User;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class UserServiceTest {

    @Test
    void ensuresDefaultRootUserCreatedWhenMissing() {
        RecordingUserDAO dao = new RecordingUserDAO();

        new UserService(dao);

        User root = dao.getStored();
        assertNotNull(root, "root user should be created");
        assertEquals("root", root.getUsername());
        assertEquals("root", root.getFullName());
        assertEquals(Role.ADMIN, root.getRole());
        assertTrue(root.isActive());
        assertNotNull(root.getPasswordHash());
        assertTrue(root.getPasswordHash().startsWith("$2"), "password must be bcrypt hashed");
        assertTrue(BCrypt.checkpw("1234", root.getPasswordHash()));
    }

    @Test
    void repairsExistingRootUserToMatchDefaults() {
        RecordingUserDAO dao = new RecordingUserDAO();
        User existing = new User("root", "plain", Role.KASIYER);
        existing.setId(42L);
        existing.setActive(false);
        dao.setExistingUser(existing);

        new UserService(dao);

        User root = dao.getStored();
        assertNotNull(root);
        assertEquals(42L, root.getId());
        assertEquals(Role.ADMIN, root.getRole());
        assertTrue(root.isActive());
        assertEquals("root", root.getFullName());
        assertTrue(root.getPasswordHash().startsWith("$2"));
        assertTrue(BCrypt.checkpw("1234", root.getPasswordHash()));
    }

    private static class RecordingUserDAO implements UserDAO {
        private User stored;

        User getStored() {
            return stored;
        }

        void setExistingUser(User user) {
            this.stored = user;
        }

        @Override
        public Optional<User> findByUsername(String username) {
            if (stored != null && Objects.equals(stored.getUsername(), username)) {
                return Optional.of(stored);
            }
            return Optional.empty();
        }

        @Override
        public Long create(User e) {
            stored = e;
            if (stored.getId() == null) {
                stored.setId(1L);
            }
            return stored.getId();
        }

        @Override
        public void update(User e) {
            stored = e;
        }

        @Override
        public Optional<User> findById(Long id) {
            if (stored != null && Objects.equals(stored.getId(), id)) {
                return Optional.of(stored);
            }
            return Optional.empty();
        }

        @Override
        public void deleteById(Long id) {
            if (stored != null && Objects.equals(stored.getId(), id)) {
                stored = null;
            }
        }

        @Override
        public List<User> findAll(int offset, int limit) {
            return stored == null ? List.of() : List.of(stored);
        }

        @Override
        public void updateRole(Long userId, Role role) {
            if (stored != null && Objects.equals(stored.getId(), userId)) {
                stored.setRole(role);
            }
        }
    }
}
