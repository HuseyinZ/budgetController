package dao;

import model.Payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface PaymentDAO extends CrudRepository<Payment, Long> {
    List<Payment> findByOrderId(Long orderId);
    BigDecimal totalPaidForOrder(Long orderId);

    // YENİ: PaymentService’in kullandığı metod
    List<Payment> findByDateRange(LocalDate startInclusive, LocalDate endExclusive);
}
