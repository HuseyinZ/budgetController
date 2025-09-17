package service;

import dao.PaymentDAO;
import dao.UserDAO;
import model.Payment;
import model.PaymentMethod;
import model.Role;
import model.User;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SaleServiceTest {

    @Test
    void exportDailyReportWritesValuesAndHandlesNulls() throws Exception {
        Payment payment1 = new Payment();
        payment1.setOrderId(1L);
        payment1.setAmount(new BigDecimal("10.55"));
        payment1.setMethod(PaymentMethod.CASH);
        payment1.setCashierId(null);
        payment1.setPaidAt(null);

        Payment payment2 = new Payment();
        payment2.setOrderId(2L);
        payment2.setAmount(new BigDecimal("5.45"));
        payment2.setMethod(PaymentMethod.CARD);
        payment2.setCashierId(7L);
        payment2.setPaidAt(LocalDateTime.of(2024, 1, 1, 12, 0));

        List<Payment> payments = List.of(payment1, payment2);
        PaymentService paymentService = new PaymentService(new StubPaymentDAO(new ArrayList<>(payments)));
        UserService userService = new UserService(new StubUserDAO(createUser(7L, "Kasiyer Test")));
        SaleService saleService = new SaleService(paymentService, userService);

        Path file = Files.createTempFile("sales", ".xlsx");
        try {
            assertTrue(saleService.exportDailySalesReport(LocalDate.of(2024, 1, 1), file.toString()));

            try (Workbook workbook = WorkbookFactory.create(new FileInputStream(file.toFile()))) {
                var sheet = workbook.getSheetAt(0);
                assertEquals("1", sheet.getRow(1).getCell(0).getStringCellValue());
                assertEquals("10.55", sheet.getRow(1).getCell(1).getStringCellValue());
                assertEquals("Unassigned", sheet.getRow(1).getCell(3).getStringCellValue());
                assertEquals("Kasiyer Test", sheet.getRow(2).getCell(3).getStringCellValue());
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void dailyTotalSumsBigDecimals() {
        Payment payment1 = new Payment();
        payment1.setAmount(new BigDecimal("2.10"));
        Payment payment2 = new Payment();
        payment2.setAmount(new BigDecimal("3.40"));
        PaymentService paymentService = new PaymentService(new StubPaymentDAO(new ArrayList<>(List.of(payment1, payment2))));
        SaleService saleService = new SaleService(paymentService, new UserService(new StubUserDAO(null)));

        BigDecimal total = saleService.getDailySalesTotal(LocalDate.now());
        assertEquals(new BigDecimal("5.50"), total);
    }

    private static User createUser(Long id, String fullName) {
        User user = new User("user" + id, "secret", Role.ADMIN, fullName);
        user.setId(id);
        return user;
    }

    private static class StubPaymentDAO implements PaymentDAO {
        private final List<Payment> payments;

        StubPaymentDAO(List<Payment> payments) {
            this.payments = payments;
        }

        @Override
        public List<Payment> findByDateRange(LocalDate startInclusive, LocalDate endExclusive) {
            return payments;
        }

        @Override
        public List<Payment> findByOrderId(Long orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BigDecimal totalPaidForOrder(Long orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long create(Payment e) { throw new UnsupportedOperationException(); }
        @Override
        public void update(Payment e) { throw new UnsupportedOperationException(); }
        @Override
        public void deleteById(Long id) { throw new UnsupportedOperationException(); }
        @Override
        public Optional<Payment> findById(Long id) { return Optional.empty(); }
        @Override
        public List<Payment> findAll(int offset, int limit) { return payments; }
    }

    private static class StubUserDAO implements UserDAO {
        private User user;

        StubUserDAO(User user) {
            this.user = user;
        }

        @Override
        public Optional<User> findById(Long id) {
            if (user != null && Objects.equals(user.getId(), id)) {
                return Optional.of(user);
            }
            return Optional.empty();
        }

        @Override
        public Optional<User> findByUsername(String username) {
            if (user != null && Objects.equals(user.getUsername(), username)) {
                return Optional.of(user);
            }
            return Optional.empty();
        }

        @Override
        public Long create(User e) {
            user = e;
            if (user.getId() == null) {
                user.setId(1L);
            }
            return user.getId();
        }

        @Override
        public void update(User e) {
            user = e;
        }

        @Override
        public void deleteById(Long id) {
            if (user != null && Objects.equals(user.getId(), id)) {
                user = null;
            }
        }

        @Override
        public List<User> findAll(int offset, int limit) {
            return user == null ? List.of() : List.of(user);
        }

        @Override
        public void updateRole(Long userId, Role role) {
            if (user != null && Objects.equals(user.getId(), userId)) {
                user.setRole(role);
            }
        }
    }
}
