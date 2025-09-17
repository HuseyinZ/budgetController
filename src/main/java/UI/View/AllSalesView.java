package UI.View;

import UI.AllSalesPanel;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class AllSalesView extends JFrame {
    public AllSalesView() {
        this(AppState.getInstance());
    }

    public AllSalesView(AppState appState) {
        super("Satış Listesi");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(new AllSalesPanel(Objects.requireNonNull(appState, "appState")), BorderLayout.CENTER);
        setSize(800, 520);
        setLocationRelativeTo(null);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
