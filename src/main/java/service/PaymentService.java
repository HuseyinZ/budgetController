package service;

import dao.PaymentDAO;
import dao.jdbc.PaymentJdbcDAO;
import model.MoneyUtil;
import model.Payment;
import model.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public class PaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentDAO paymentDAO;

    public PaymentService() {
        this(new PaymentJdbcDAO());
    }

    public PaymentService(PaymentDAO paymentDAO) {
        this.paymentDAO = Objects.requireNonNull(paymentDAO, "paymentDAO");
    }

    public List<Payment> getAllPayments() {
        return paymentDAO.findAll(0, Integer.MAX_VALUE);
    }

    public List<Payment> getPaymentsBetween(LocalDate startDate, LocalDate endDate) {
        return paymentDAO.findByDateRange(startDate, endDate);
    }

    public List<Payment> getPaymentsOn(LocalDate date) {
        return paymentDAO.findByDateRange(date, date.plusDays(1));
    }

    public List<Payment> getPaymentsInMonth(int year, int month) {
        LocalDate firstDay = LocalDate.of(year, month, 1);
        return paymentDAO.findByDateRange(firstDay, firstDay.plusMonths(1));
    }

    /**
     * Tek bir ödeme parçası kaydı ekler — hesap bölme için.
     * Order kapatma işlemini YAPMAZ; caller tarafında Order COMPLETED yapılmalı.
     */
    public Long recordPayment(Long orderId, Long cashierId, BigDecimal amount,
                              PaymentMethod method) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("orderId gerekli");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Tutar > 0 olmalı");
        }
        if (method == null) {
            throw new IllegalArgumentException("Ödeme yöntemi gerekli");
        }
        Payment p = new Payment();
        p.setOrderId(orderId);
        p.setCashierId(cashierId);
        p.setAmount(MoneyUtil.two(amount));
        p.setMethod(method);
        p.setPaidAt(LocalDateTime.now());
        return paymentDAO.create(p);
    }

    public boolean exportPaymentsToExcel(List<Payment> payments, String filePath) {
        return executeIo(LOG, "Ödemeler Excel'e aktarılamadı: {}", () -> {
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                XSSFSheet sheet = workbook.createSheet("Payments");
                createHeaderRow(sheet, "Payment ID", "Order ID", "Cashier ID", "Amount", "Method", "Paid At");

                int rowIndex = 1;
                for (Payment pay : payments) {
                    Row row = sheet.createRow(rowIndex++);
                    row.createCell(0).setCellValue(pay.getId());
                    row.createCell(1).setCellValue(pay.getOrderId());
                    row.createCell(2).setCellValue(pay.getCashierId() == null ? "" : pay.getCashierId().toString());
                    row.createCell(3).setCellValue(
                            pay.getAmount() == null ? "" : MoneyUtil.two(pay.getAmount()).toPlainString());
                    row.createCell(4).setCellValue(pay.getMethod() == null ? "" : pay.getMethod().toString());
                    row.createCell(5).setCellValue(
                            pay.getPaidAt() == null ? "" : pay.getPaidAt().toString()
                    );
                }
                for (int i = 0; i <= 5; i++) {
                    sheet.autoSizeColumn(i);
                }
                try (FileOutputStream out = new FileOutputStream(filePath)) {
                    workbook.write(out);
                }
            }
        });
    }

    static void createHeaderRow(Sheet sheet, String... columnNames) {
        Row header = sheet.createRow(0);
        for (int columnIndex = 0; columnIndex < columnNames.length; columnIndex++) {
            header.createCell(columnIndex).setCellValue(columnNames[columnIndex]);
        }
    }

    static boolean executeIo(Logger logger, String errorMessage, IoAction action) {
        try {
            action.run();
            return true;
        } catch (IOException e) {
            logger.error(errorMessage, e.getMessage(), e);
            return false;
        }
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }
}
