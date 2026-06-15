package dao;

import model.KitchenPrinter;

import java.util.List;
import java.util.Optional;

public interface KitchenPrinterDAO extends CrudRepository<KitchenPrinter, Integer> {

    Optional<KitchenPrinter> findByCode(String code);

    List<KitchenPrinter> findActive();
}
