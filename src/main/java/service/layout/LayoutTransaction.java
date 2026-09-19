package service.layout;

import DataConnection.Db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Masa düzeni yazma işlemleri için transaction sınırı.
 *
 * <p>Soyutlama testler içindir: gerçek bir veritabanı olmadan commit/rollback
 * davranışı doğrulanabilsin diye {@link RestaurantLayoutManagementService}
 * bağlantıyı doğrudan açmaz.
 */
@FunctionalInterface
public interface LayoutTransaction {

    /** Transaction içinde çalıştırılacak iş. */
    @FunctionalInterface
    interface Work<T> {
        T apply(Connection conn) throws Exception;
    }

    <T> T execute(Work<T> work);

    /**
     * Üretim uygulaması: uygulama havuzundan bağlantı alır, auto-commit'i
     * kapatır, iş başarılıysa commit, hata halinde rollback eder ve önceki
     * auto-commit değerini geri yükler.
     */
    static LayoutTransaction usingApplicationPool() {
        return new LayoutTransaction() {
            @Override
            public <T> T execute(Work<T> work) {
                try (Connection conn = Db.getConnection()) {
                    boolean previousAutoCommit = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try {
                        T result = work.apply(conn);
                        conn.commit();
                        return result;
                    } catch (Exception ex) {
                        safeRollback(conn);
                        throw ex instanceof RuntimeException re
                                ? re
                                : new IllegalStateException("Masa düzeni işlemi geri alındı", ex);
                    } finally {
                        try {
                            conn.setAutoCommit(previousAutoCommit);
                        } catch (SQLException ignored) {
                            // bağlantı kapanıyor; auto-commit geri yüklenemezse önemsiz
                        }
                    }
                } catch (SQLException ex) {
                    throw new IllegalStateException("Masa düzeni bağlantısı açılamadı (SQLState="
                            + ex.getSQLState() + ")", ex);
                }
            }
        };
    }

    private static void safeRollback(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // rollback başarısızsa bağlantı kapanışı zaten işlemi iptal eder
        }
    }
}
