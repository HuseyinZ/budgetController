package dao;

import model.PrintJob;

import java.util.List;
import java.util.Optional;

public interface PrintJobDAO {

    Long enqueue(PrintJob job);

    Optional<PrintJob> findById(Long id);

    /** Bekleyen + başarısız işleri (yeniden deneme için) en eski tarihten itibaren döner. */
    List<PrintJob> findPending(int limit);

    void markPrinted(Long id);

    void markFailed(Long id, String error);
}
