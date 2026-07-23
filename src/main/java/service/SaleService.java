package service;

import model.MoneyUtil;
import model.Payment;
import model.PaymentMethod;
import model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

public class SaleService {

    private static final Logger LOG = LoggerFactory.getLogger(SaleService.class);

    private final PaymentService paymentService;
    private final UserService userService;

    public SaleService() {
        this(new PaymentService(), new UserService());
    }

    public SaleService(PaymentService paymentService, UserService userService) {
        this.paymentService = Objects.requireNonNull(paymentService, "paymentService");
        this.userService = Objects.requireNonNull(userService, "userService");
    }

    public BigDecimal getDailySalesTotal(LocalDate date) {
        List<Payment> payments = paymentService.getPaymentsOn(date);
        return MoneyUtil.sumAmounts(payments, Payment::getAmount);
    }

    public BigDecimal getMonthlySalesTotal(int year, int month) {
        List<Payment> payments = paymentService.getPaymentsInMonth(year, month);
        return MoneyUtil.sumAmounts(payments, Payment::getAmount);
    }

    public List<Payment> getDailySalesList(LocalDate date) {
        return paymentService.getPaymentsOn(date);
    }

    public boolean exportDailySalesReport(LocalDate date, String filePath) {
        List<Payment> payments = paymentService.getPaymentsOn(date);
        return writeWorkbook(filePath, workbook -> {
            Sheet sheet = workbook.createSheet("DailySales_" + date);
            PaymentService.createHeaderRow(
                    sheet, "Order ID", "Amount", "Payment Method", "Cashier", "Paid At");

            int rowIndex = 1;
            for (Payment p : payments) {
                Row row = sheet.createRow(rowIndex++);
                // Order ID'yi STRING olarak yaz — sayısal hücre dönüşümleri
                // (örn. "1" yerine "1.0") önlemek için.
                Long orderId = p.getOrderId();
                row.createCell(0).setCellValue(orderId == null ? "" : String.valueOf(orderId));

                BigDecimal amount = MoneyUtil.two(p.getAmount());
                row.createCell(1).setCellValue(amount.toPlainString());

                PaymentMethod method = p.getMethod();
                row.createCell(2).setCellValue(method == null ? "" : method.toString());

                Long cashierId = p.getCashierId();
                String cashierName = cashierId == null
                        ? "Unassigned"
                        : userService.getUserById(cashierId)
                        .map(User::getFullName)
                        .orElse("Unknown");
                row.createCell(3).setCellValue(cashierName);

                LocalDateTime paidAt = p.getPaidAt();
                row.createCell(4).setCellValue(paidAt == null ? "N/A" : paidAt.toString());
            }

            PaymentService.autoSizeColumns(sheet, 5);
        });
    }

    public boolean exportMonthlySalesReport(int year, int month, String filePath) {
        List<Payment> payments = paymentService.getPaymentsInMonth(year, month);
        Map<LocalDate, DailySales> dailySales = new TreeMap<>();
        for (Payment p : payments) {
            LocalDateTime paidAt = p.getPaidAt();
            if (paidAt == null) {
                continue;
            }
            LocalDate day = paidAt.toLocalDate();
            BigDecimal amount = p.getAmount() == null ? BigDecimal.ZERO : p.getAmount();
            dailySales.computeIfAbsent(day, ignored -> new DailySales()).add(amount);
        }

        return writeWorkbook(filePath, workbook -> {
            Sheet sheet = workbook.createSheet("Sales_" + year + "-" + month);
            PaymentService.createHeaderRow(sheet, "Date", "Total Sales", "Number of Sales");

            int rowIndex = 1;
            BigDecimal monthTotal = BigDecimal.ZERO;
            int monthCount = 0;
            for (Map.Entry<LocalDate, DailySales> entry : dailySales.entrySet()) {
                LocalDate day = entry.getKey();
                DailySales sales = entry.getValue();
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(day.toString());
                BigDecimal amount = MoneyUtil.two(sales.amount);
                row.createCell(1).setCellValue(amount.toPlainString());
                row.createCell(2).setCellValue(sales.count);
                monthTotal = monthTotal.add(sales.amount);
                monthCount += sales.count;
            }

            Row totalRow = sheet.createRow(rowIndex);
            totalRow.createCell(0).setCellValue("TOTAL");
            totalRow.createCell(1).setCellValue(MoneyUtil.two(monthTotal).toPlainString());
            totalRow.createCell(2).setCellValue(monthCount);

            PaymentService.autoSizeColumns(sheet, 3);
        });
    }

    private static final class DailySales {
        private BigDecimal amount = BigDecimal.ZERO;
        private int count;

        private void add(BigDecimal paymentAmount) {
            amount = amount.add(paymentAmount);
            count++;
        }
    }

    private boolean writeWorkbook(String filePath, Consumer<Workbook> workbookWriter) {
        return PaymentService.executeIo(LOG, "Satış Excel'e aktarılamadı: {}", () -> {
            try (Workbook workbook = new XSSFWorkbook();
                 FileOutputStream fileOut = new FileOutputStream(filePath)) {
                workbookWriter.accept(workbook);
                workbook.write(fileOut);
                fileOut.flush();
            }
        });
    }
}
