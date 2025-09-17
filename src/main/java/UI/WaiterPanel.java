package UI;

import model.User;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class WaiterPanel extends JPanel {
    public WaiterPanel(AppState appState, User user) {
        setLayout(new BorderLayout());
        Objects.requireNonNull(appState, "appState");
        Objects.requireNonNull(user, "user");

        JLabel title = new JLabel("Garson Paneli");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(title, BorderLayout.NORTH);

        RestaurantTablesPanel tablesPanel = new RestaurantTablesPanel(appState, user);
        add(new JScrollPane(tablesPanel), BorderLayout.CENTER);
    }
}
