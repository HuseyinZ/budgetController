package service;

import dao.PaymentDAO;
import dao.jdbc.PaymentJdbcDAO;
import model.Payment;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.ss.usermodel.Row;

import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

public class PaymentService {
    private final PaymentDAO paymentDAO = new PaymentJdbcDAO();

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

    public boolean exportPaymentsToExcel(List<Payment> payments, String filePath) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Payments");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Payment ID");
            header.createCell(1).setCellValue("Order ID");
            header.createCell(2).setCellValue("Cashier ID");
            header.createCell(3).setCellValue("Amount");
            header.createCell(4).setCellValue("Method");
            header.createCell(5).setCellValue("Paid At");

            int rowIndex = 1;
            for (Payment pay : payments) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(pay.getId());
                row.createCell(1).setCellValue(pay.getOrderId());
                row.createCell(2).setCellValue(pay.getCashierId() == null ? -1 : pay.getCashierId());
                row.createCell(3).setCellValue(pay.getAmount().doubleValue());
                row.createCell(4).setCellValue(pay.getMethod().toString());
                row.createCell(5).setCellValue(
                        pay.getPaidAt() == null ? "" : pay.getPaidAt().toString()
                );
            }
            try (FileOutputStream out = new FileOutputStream(filePath)) {
                workbook.write(out);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
}
