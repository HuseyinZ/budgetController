package dao;

import model.RefundLog;

import java.time.LocalDate;
import java.util.List;

/**
 * İade / iptal / silme işlemlerinin denetim (audit) günlüğü.
 * Append-only — log kaydı silinmez, düzenlenmez.
 */
public interface RefundLogDAO {

    /** Yeni log kaydı ekler ve oluşturulan id'yi döner. */
    Long create(RefundLog log);

    /** Tüm log kayıtlarını en yeni en üstte olacak şekilde döner. */
    List<RefundLog> findAll();

    /** Belirli tarih aralığındaki log kayıtları. */
    List<RefundLog> findByDateRange(LocalDate fromInclusive, LocalDate toInclusive);

    /** Bir kullanıcının yaptığı tüm iade işlemleri. */
    List<RefundLog> findByUserId(Long userId);
}
