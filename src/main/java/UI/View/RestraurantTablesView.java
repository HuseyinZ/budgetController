package UI.View;

import UI.RestaurantTablesPanel;
import model.User;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class RestraurantTablesView extends JFrame {
    public RestraurantTablesView(AppState appState, User user) {
        super("Restoran Masaları");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        RestaurantTablesPanel panel = new RestaurantTablesPanel(Objects.requireNonNull(appState, "appState"), Objects.requireNonNull(user, "user"));
        add(new JScrollPane(panel), BorderLayout.CENTER);
        setSize(900, 600);
        setLocationRelativeTo(null);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
