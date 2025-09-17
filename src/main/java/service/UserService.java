package service;

import dao.UserDAO;
import dao.jdbc.UserJdbcDAO;
import model.Role;
import model.User;
import org.mindrot.jbcrypt.BCrypt;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class UserService {

    private final UserDAO userDAO;

    public UserService() {
        this(new UserJdbcDAO());
    }

    public UserService(UserDAO userDAO) {
        this.userDAO = Objects.requireNonNull(userDAO, "userDAO");
        ensureDefaultAdminUser();
    }

    public Optional<User> getUserById(Long userId) {
        return userDAO.findById(userId);
    }

    public Optional<User> getUserByUsername(String username) {
        return userDAO.findByUsername(username);
    }

    public List<User> getAllUsers() {
        return userDAO.findAll(0, Integer.MAX_VALUE);
    }

    public User login(String username, String rawPassword) {
        Optional<User> opt = userDAO.findByUsername(username);
        if (opt.isEmpty()) return null;

        User user = opt.get();
        if (!passwordMatches(user.getPasswordHash(), rawPassword) || !user.isActive()) return null;
        return user;
    }

    private void ensureDefaultAdminUser() {
        try {
            Optional<User> existingRoot = userDAO.findByUsername("root");
            if (existingRoot.isEmpty()) {
                createUser("root", "1234", Role.ADMIN, "root");
                return;
            }

            User rootUser = existingRoot.get();
            boolean updated = false;

            if (!rootUser.isActive()) {
                rootUser.setActive(true);
                updated = true;
            }

            if (rootUser.getRole() != Role.ADMIN) {
                rootUser.setRole(Role.ADMIN);
                updated = true;
            }

            String fullName = rootUser.getFullName();
            if (fullName == null || fullName.isBlank()) {
                rootUser.setFullName("root");
                updated = true;
            }

            if (!passwordMatches(rootUser.getPasswordHash(), "1234")) {
                rootUser.setPasswordHash(BCrypt.hashpw("1234", BCrypt.gensalt()));
                updated = true;
            }

            if (updated) {
                userDAO.update(rootUser);
            }
        } catch (UnsupportedOperationException ignored) {
            // DAO implementation does not support bootstrap operations (e.g. in tests)
        } catch (RuntimeException ex) {
            System.err.println("Default admin bootstrap failed: " + ex.getMessage());
        }
    }

    private boolean passwordMatches(String storedHash, String rawPassword) {
        if (storedHash == null || rawPassword == null) {
            return false;
        }
        if (storedHash.startsWith("$2")) {
            try {
                return BCrypt.checkpw(rawPassword, storedHash);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return rawPassword.equals(storedHash);
    }

    public Long createUser(String username, String rawPassword, Role role, String fullName) {
        String safeFullName = (fullName == null || fullName.isBlank()) ? username : fullName;
        String hash = BCrypt.hashpw(rawPassword, BCrypt.gensalt());

        User user = new User(username, hash, role, safeFullName);
        return userDAO.create(user);
    }

    public void updateUser(User user) {
        userDAO.update(user);
    }

    public void deleteUser(Long userId) {
        userDAO.deleteById(userId);
    }

    public void setUserRole(Long userId, Role role) {
        userDAO.updateRole(userId, role);
    }

    public void activate(Long userId) {
        userDAO.findById(userId).ifPresent(u -> {
            u.setActive(true);
            userDAO.update(u);
        });
    }

    public void deactivate(Long userId) {
        userDAO.findById(userId).ifPresent(u -> {
            u.setActive(false);
            userDAO.update(u);
        });
    }

    public void changePassword(Long userId, String newRawPassword) {
        String newHash = BCrypt.hashpw(newRawPassword, BCrypt.gensalt());
        userDAO.findById(userId).ifPresent(u -> {
            u.setPasswordHash(newHash);
            userDAO.update(u);
        });
    }
}
