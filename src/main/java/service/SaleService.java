package service;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import model.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class SaleService {
    private PaymentService paymentService = new PaymentService();
    private UserService userService = new UserService();  // for retrieving cashier names

    /**
     * Get total sales amount for a specific date.
     */
    public BigDecimal getDailySalesTotal(LocalDate date) {
        List<Payment> payments = paymentService.getPaymentsOn(date);
        BigDecimal total = BigDecimal.ZERO;
        for (Payment p : payments) {
            total = total.add(p.getAmount());
        }
        return total;
    }

    /**
     * Get total sales amount for a specific month.
     * @param year e.g. 2025
     * @param month e.g. 9 for September
     */
    public BigDecimal getMonthlySalesTotal(int year, int month) {
        List<Payment> payments = paymentService.getPaymentsInMonth(year, month);
        BigDecimal total = BigDecimal.ZERO;
        for (Payment p : payments) {
            total = total.add(p.getAmount());
        }
        return total;
    }

    /**
     * Get list of all sales (payments) for a specific date.
     * Each Payment contains orderId, amount, cashierId, method, etc.
     */
    public List<Payment> getDailySalesList(LocalDate date) {
        return paymentService.getPaymentsOn(date);
    }

    /**
     * Generate an Excel report for all sales of a given day, and save to a file.
     * @param date The date for the report (sales of this day).
     * @param filePath The file path where the Excel report will be saved.
     * @return true if the report was generated and saved successfully.
     */
    public boolean exportDailySalesReport(LocalDate date, String filePath) {
        List<Payment> payments = paymentService.getPaymentsOn(date);
        try (Workbook workbook = new XSSFWorkbook();
             FileOutputStream fileOut = new FileOutputStream(filePath)) {
            Sheet sheet = workbook.createSheet("DailySales_" + date);
            // Header row
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Order ID");
            header.createCell(1).setCellValue("Amount");
            header.createCell(2).setCellValue("Payment Method");
            header.createCell(3).setCellValue("Cashier");
            header.createCell(4).setCellValue("Paid At");
            // Data rows
            int rowIndex = 1;
            for (Payment p : payments) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(p.getOrderId());
                // Amount as numeric (assuming currency in two decimals)
                row.createCell(1).setCellValue(p.getAmount().doubleValue());
                row.createCell(2).setCellValue(p.getMethod().toString());
                // Retrieve cashier name from UserService
                String cashierName = userService.getUserById(p.getCashierId())
                        .map(User::getFullName)
                        .orElse("Unknown");
                row.createCell(3).setCellValue(cashierName);
                // PaidAt timestamp as string
                row.createCell(4).setCellValue(p.getPaidAt().toString());
            }
            // Autosize columns for better readability
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

    /**
     * Generate an Excel report for all sales in a given month, with daily totals.
     * @param year Year of the month (e.g., 2025).
     * @param month Month number (1-12).
     * @param filePath File path to save the Excel report.
     * @return true if successful.
     */
    public boolean exportMonthlySalesReport(int year, int month, String filePath) {
        List<Payment> payments = paymentService.getPaymentsInMonth(year, month);
        // Calculate daily totals and counts
        Map<LocalDate, BigDecimal> dailyTotal = new TreeMap<>();
        Map<LocalDate, Integer> dailyCount = new TreeMap<>();
        for (Payment p : payments) {
            LocalDate day = p.getPaidAt().toLocalDate();
            // accumulate total for the day
            dailyTotal.put(day, dailyTotal.getOrDefault(day, BigDecimal.ZERO).add(p.getAmount()));
            // accumulate count for the day
            dailyCount.put(day, dailyCount.getOrDefault(day, 0) + 1);
        }
        // Calculate overall total and count for the month
        BigDecimal monthTotal = BigDecimal.ZERO;
        int monthCount = 0;
        for (BigDecimal dailySum : dailyTotal.values()) {
            monthTotal = monthTotal.add(dailySum);
        }
        for (Integer count : dailyCount.values()) {
            monthCount += count;
        }
        // Create Excel workbook
        try (Workbook workbook = new XSSFWorkbook();
             FileOutputStream fileOut = new FileOutputStream(filePath)) {
            Sheet sheet = workbook.createSheet("Sales_" + year + "-" + month);
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Date");
            header.createCell(1).setCellValue("Total Sales");
            header.createCell(2).setCellValue("Number of Sales");
            // Fill daily rows
            int rowIndex = 1;
            for (LocalDate day : dailyTotal.keySet()) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(day.toString());
                row.createCell(1).setCellValue(dailyTotal.get(day).doubleValue());
                row.createCell(2).setCellValue(dailyCount.get(day));
            }
            // Summary row for the whole month
            Row totalRow = sheet.createRow(rowIndex);
            totalRow.createCell(0).setCellValue("TOTAL");
            totalRow.createCell(1).setCellValue(monthTotal.doubleValue());
            totalRow.createCell(2).setCellValue(monthCount);
            // Autosize columns
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
