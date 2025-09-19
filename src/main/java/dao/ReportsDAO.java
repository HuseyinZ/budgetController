package dao;

import model.ProductSalesRow;

import java.time.LocalDateTime;
import java.util.List;

public interface ReportsDAO {
    List<ProductSalesRow> findProductSalesBefore(LocalDateTime threshold);
}
