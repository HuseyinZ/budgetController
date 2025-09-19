package service;

import dao.ReportsDAO;
import dao.jdbc.ReportsJdbcDAO;
import model.ProductSalesRow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public class ReportsService {

    private final ReportsDAO reportsDAO;

    public ReportsService() {
        this(new ReportsJdbcDAO());
    }

    public ReportsService(ReportsDAO reportsDAO) {
        this.reportsDAO = Objects.requireNonNull(reportsDAO, "reportsDAO");
    }

    public List<ProductSalesRow> getProductSalesBefore(LocalDateTime threshold) {
        return reportsDAO.findProductSalesBefore(threshold);
    }
}
