package UI.View;

import UI.AllSalesPanel;
import service.SaleService;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class AllSalesView extends JFrame {
    public AllSalesView() {
        this(new SaleService());
    }

    public AllSalesView(SaleService saleService) {
        super("Günlük Satışlar");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(new AllSalesPanel(Objects.requireNonNull(saleService)), BorderLayout.CENTER);
        setSize(800, 500);
        setLocationRelativeTo(null);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
