package service;

import dao.UserDAO;
import dao.jdbc.UserJdbcDAO;
import model.Role;
import model.User;
import org.mindrot.jbcrypt.BCrypt;

import java.util.List;
import java.util.Optional;

public class UserService {

    private final UserDAO userDAO = new UserJdbcDAO();

    /* --------- Sorgular --------- */

    public Optional<User> getUserById(Long userId) {
        return userDAO.findById(userId);
    }

    public Optional<User> getUserByUsername(String username) {
        return userDAO.findByUsername(username);
    }

    public List<User> getAllUsers() {
        // CrudRepository imzana uygun olarak offset/limit veriyoruz
        return userDAO.findAll(0, Integer.MAX_VALUE);
    }

    /* --------- Kimlik Doğrulama --------- */

    /** Doğru kullanıcı ve parola ise User döner; değilse null. */
    public User login(String username, String rawPassword) {
        Optional<User> opt = userDAO.findByUsername(username);
        if (opt.isEmpty()) return null;

        User user = opt.get();
        String hash = user.getPasswordHash();

        boolean ok;
        if (hash != null && hash.startsWith("$2")) { // BCrypt hash
            ok = BCrypt.checkpw(rawPassword, hash);
        } else {
            // Geçici/seed veri için plain karşılaştırma (istersen tamamen kaldır)
            ok = rawPassword != null && rawPassword.equals(hash);
        }

        if (!ok || !user.isActive()) return null;
        return user;
    }

    /* --------- Yönetim İşlemleri --------- */

    /** Yeni kullanıcı oluşturur ve ID döner. fullName zorunlu ise boşsa username kullanılır. */
    public Long createUser(String username, String rawPassword, Role role, String fullName) {
        String safeFullName = (fullName == null || fullName.isBlank()) ? username : fullName;
        String hash = BCrypt.hashpw(rawPassword, BCrypt.gensalt());

        // Modelinde bu ctor var (UserJdbcDAO da bunu kullanıyor):
        User user = new User(username, hash, role, safeFullName);
        return userDAO.create(user);
    }

    /** Kullanıcıyı güncelle (username, fullName, aktiflik vb.). */
    public void updateUser(User user) {
        userDAO.update(user);
    }

    /** Kullanıcıyı sil. */
    public void deleteUser(Long userId) {
        userDAO.deleteById(userId);
    }

    /** Rol güncelle. */
    public void setUserRole(Long userId, Role role) {
        userDAO.updateRole(userId, role);
    }

    /** Kullanıcıyı aktif yap. */
    public void activate(Long userId) {
        userDAO.findById(userId).ifPresent(u -> {
            u.setActive(true);
            userDAO.update(u);
        });
    }

    /** Kullanıcıyı pasif yap. */
    public void deactivate(Long userId) {
        userDAO.findById(userId).ifPresent(u -> {
            u.setActive(false);
            userDAO.update(u);
        });
    }

    /** Parolayı değiştir. */
    public void changePassword(Long userId, String newRawPassword) {
        String newHash = BCrypt.hashpw(newRawPassword, BCrypt.gensalt());
        userDAO.findById(userId).ifPresent(u -> {
            u.setPasswordHash(newHash);
            userDAO.update(u);
        });
    }
}