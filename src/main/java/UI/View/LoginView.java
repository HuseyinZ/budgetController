package UI.View;

import UI.LoginPanel;
import model.User;
import service.UserService;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;
import java.util.function.Consumer;

public class LoginView extends JFrame {
    private final LoginPanel loginPanel;

    public LoginView() {
        this(new UserService());
    }

    public LoginView(UserService userService) {
        super("Budget Controller - Giriş");
        this.loginPanel = new LoginPanel(Objects.requireNonNull(userService));
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        add(loginPanel, BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(null);
    }

    public void setLoginListener(Consumer<User> listener) {
        loginPanel.setLoginListener(listener);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
