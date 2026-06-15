package dao;

import model.CategoryPrinterRoute;

import java.util.List;

public interface CategoryPrinterRouteDAO {

    /** Bir kategoriye düşen tüm yazıcıları döner. */
    List<Integer> findPrinterIdsByCategory(Long categoryId);

    /** Tüm yönlendirmeler — admin paneli için. */
    List<CategoryPrinterRoute> findAll();

    /** Yeni yönlendirme ekler; varsa hata atmaz (UNIQUE constraint sayesinde). */
    void link(Long categoryId, Integer printerId);

    /** Yönlendirmeyi kaldırır. */
    void unlink(Long categoryId, Integer printerId);

    /** Belirli kategoriye ait tüm yönlendirmeleri siler. */
    void deleteByCategory(Long categoryId);
}
