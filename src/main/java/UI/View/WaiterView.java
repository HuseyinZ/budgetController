package UI.View;

import UI.WaiterPanel;
import model.User;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class WaiterView extends JFrame {
    public WaiterView(AppState appState, User user) {
        super("Garson Paneli");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(new WaiterPanel(Objects.requireNonNull(appState, "appState"), Objects.requireNonNull(user, "user")), BorderLayout.CENTER);
        setSize(900, 600);
        setLocationRelativeTo(null);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
