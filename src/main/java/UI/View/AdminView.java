package UI.View;

import UI.AdminPanel;
import service.UserService;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class AdminView extends JFrame {
    public AdminView() {
        this(new UserService());
    }

    public AdminView(UserService userService) {
        super("Kullanıcı Yönetimi");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(new AdminPanel(Objects.requireNonNull(userService)), BorderLayout.CENTER);
        setSize(720, 480);
        setLocationRelativeTo(null);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
