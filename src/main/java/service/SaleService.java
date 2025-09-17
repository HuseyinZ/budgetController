package service;

import model.Payment;
import model.PaymentMethod;
import model.User;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public class SaleService {
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
        BigDecimal total = BigDecimal.ZERO;
        for (Payment p : payments) {
            if (p.getAmount() != null) {
                total = total.add(p.getAmount());
            }
        }
        return total;
    }

    public BigDecimal getMonthlySalesTotal(int year, int month) {
        List<Payment> payments = paymentService.getPaymentsInMonth(year, month);
        BigDecimal total = BigDecimal.ZERO;
        for (Payment p : payments) {
            if (p.getAmount() != null) {
                total = total.add(p.getAmount());
            }
        }
        return total;
    }

    public List<Payment> getDailySalesList(LocalDate date) {
        return paymentService.getPaymentsOn(date);
    }

    public boolean exportDailySalesReport(LocalDate date, String filePath) {
        List<Payment> payments = paymentService.getPaymentsOn(date);
        try (Workbook workbook = new XSSFWorkbook();
             FileOutputStream fileOut = new FileOutputStream(filePath)) {
            Sheet sheet = workbook.createSheet("DailySales_" + date);
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Order ID");
            header.createCell(1).setCellValue("Amount");
            header.createCell(2).setCellValue("Payment Method");
            header.createCell(3).setCellValue("Cashier");
            header.createCell(4).setCellValue("Paid At");

            int rowIndex = 1;
            for (Payment p : payments) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(p.getOrderId());

                BigDecimal amount = p.getAmount() == null ? BigDecimal.ZERO : p.getAmount();
                amount = amount.setScale(2, RoundingMode.HALF_UP);
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

            for (int col = 0; col <= 4; col++) {
                sheet.autoSizeColumn(col);
            }
            workbook.write(fileOut);
            fileOut.flush();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean exportMonthlySalesReport(int year, int month, String filePath) {
        List<Payment> payments = paymentService.getPaymentsInMonth(year, month);
        Map<LocalDate, BigDecimal> dailyTotal = new TreeMap<>();
        Map<LocalDate, Integer> dailyCount = new TreeMap<>();
        for (Payment p : payments) {
            LocalDateTime paidAt = p.getPaidAt();
            if (paidAt == null) {
                continue;
            }
            LocalDate day = paidAt.toLocalDate();
            BigDecimal amount = p.getAmount() == null ? BigDecimal.ZERO : p.getAmount();
            dailyTotal.put(day, dailyTotal.getOrDefault(day, BigDecimal.ZERO).add(amount));
            dailyCount.put(day, dailyCount.getOrDefault(day, 0) + 1);
        }

        BigDecimal monthTotal = BigDecimal.ZERO;
        int monthCount = 0;
        for (Map.Entry<LocalDate, BigDecimal> entry : dailyTotal.entrySet()) {
            monthTotal = monthTotal.add(entry.getValue());
            monthCount += dailyCount.getOrDefault(entry.getKey(), 0);
        }

        try (Workbook workbook = new XSSFWorkbook();
             FileOutputStream fileOut = new FileOutputStream(filePath)) {
            Sheet sheet = workbook.createSheet("Sales_" + year + "-" + month);
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Date");
            header.createCell(1).setCellValue("Total Sales");
            header.createCell(2).setCellValue("Number of Sales");

            int rowIndex = 1;
            for (LocalDate day : dailyTotal.keySet()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(day.toString());
                BigDecimal amount = dailyTotal.get(day).setScale(2, RoundingMode.HALF_UP);
                row.createCell(1).setCellValue(amount.toPlainString());
                row.createCell(2).setCellValue(dailyCount.get(day));
            }

            Row totalRow = sheet.createRow(rowIndex);
            totalRow.createCell(0).setCellValue("TOTAL");
            totalRow.createCell(1).setCellValue(monthTotal.setScale(2, RoundingMode.HALF_UP).toPlainString());
            totalRow.createCell(2).setCellValue(monthCount);

            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);
            sheet.autoSizeColumn(2);
            workbook.write(fileOut);
            fileOut.flush();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}
