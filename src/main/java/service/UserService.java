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
        String hash = user.getPasswordHash();

        boolean ok;
        if (hash != null && hash.startsWith("$2")) {
            ok = BCrypt.checkpw(rawPassword, hash);
        } else {
            ok = rawPassword != null && rawPassword.equals(hash);
        }

        if (!ok || !user.isActive()) return null;
        return user;
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
    /**
     * Uygulama başlarken çağır: admin kullanıcısı yoksa oluşturur.
     * @return true => oluşturuldu, false => zaten vardı
     */
    public boolean seedAdminIfNotExists() {
        Optional<User> existing = userDAO.findByUsername("admin");
        if (existing.isPresent()) {
            return false; // zaten var
        }
        // fullName boş gelirse createUser içinde username'e eşitlenecek
        createUser("admin", "1234", Role.ADMIN, "Admin User");
        return true;
    }

    /**
     * (İsteğe bağlı) Gerekirse açıkça çağırabileceğin kısa yardımcı.
     */
    public Long createAdmin(String rawPassword) {
        return createUser("admin", rawPassword, Role.ADMIN, "Admin User");
    }

}
