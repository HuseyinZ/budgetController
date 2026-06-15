package dao;

import model.UserAreaPermission;

import java.util.List;

public interface UserAreaPermissionDAO {

    /** Bir kullanıcıya ait tüm izinleri döner. */
    List<UserAreaPermission> findByUserId(Long userId);

    /** Yeni izin ekler; aynı satır zaten varsa sessiz geçer. */
    void grant(Long userId, String building, String section);

    /** Belirli izni kaldırır. */
    void revoke(Long userId, String building, String section);

    /** Bir kullanıcının tüm izinlerini siler (genelde tam yeniden atama öncesi). */
    void deleteAllForUser(Long userId);
}
