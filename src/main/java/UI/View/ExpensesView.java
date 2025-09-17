package UI.View;

import UI.ExpensesPanel;
import model.User;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class ExpensesView extends JFrame {
    public ExpensesView(AppState appState, User user) {
        super("Giderler");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(new ExpensesPanel(Objects.requireNonNull(appState, "appState"), Objects.requireNonNull(user, "user")), BorderLayout.CENTER);
        setSize(720, 480);
        setLocationRelativeTo(null);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
