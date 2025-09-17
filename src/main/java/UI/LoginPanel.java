package UI;

import model.User;
import service.UserService;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.Objects;
import java.util.function.Consumer;

public class LoginPanel extends JPanel {
    private final UserService userService;
    private final JTextField usernameField = new JTextField(20);
    private final JPasswordField passwordField = new JPasswordField(20);
    private final JButton loginButton = new JButton("Giriş yap");
    private final JLabel statusLabel = new JLabel(" ");
    private Consumer<User> loginListener = user -> {};

    public LoginPanel() {
        this(new UserService());
    }

    public LoginPanel(UserService userService) {
        this.userService = Objects.requireNonNull(userService, "userService");
        buildUi();
    }

    private void buildUi() {
        setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("Budget Controller");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2; gc.anchor = GridBagConstraints.CENTER;
        add(title, gc);

        gc.gridwidth = 1; gc.anchor = GridBagConstraints.WEST;
        gc.gridy = 1; gc.gridx = 0;
        add(new JLabel("Kullanıcı adı"), gc);
        gc.gridx = 1;
        add(usernameField, gc);

        gc.gridy = 2; gc.gridx = 0;
        add(new JLabel("Parola"), gc);
        gc.gridx = 1;
        add(passwordField, gc);

        gc.gridy = 3; gc.gridx = 0; gc.gridwidth = 2;
        gc.anchor = GridBagConstraints.CENTER;
        add(loginButton, gc);

        gc.gridy = 4;
        statusLabel.setForeground(Color.DARK_GRAY);
        add(statusLabel, gc);

        loginButton.addActionListener(this::handleLogin);
        passwordField.addActionListener(this::handleLogin);
    }

    public void setLoginListener(Consumer<User> loginListener) {
        this.loginListener = loginListener == null ? user -> {} : loginListener;
    }

    private void handleLogin(ActionEvent e) {
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());
        if (username.isEmpty() || password.isEmpty()) {
            showError("Kullanıcı adı ve parola gerekli");
            return;
        }
        User user = userService.login(username, password);
        if (user == null) {
            showError("Giriş başarısız");
            return;
        }

        showInfo("Hoş geldiniz, " + user.getFullName());

        try {
            loginListener.accept(user);
        } catch (Exception ex) {
            showError("Kontrol paneli açılırken hata: " + ex.getMessage());
            ex.printStackTrace();
            return;
        }

        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.dispose();

        } finally {
            Window window = SwingUtilities.getWindowAncestor(this);
            if (window != null) {
                window.dispose();
            }
        }
    }

    private void showError(String message) {
        statusLabel.setForeground(Color.RED.darker());
        statusLabel.setText(message);
    }

    private void showInfo(String message) {
        statusLabel.setForeground(new Color(0, 128, 0));
        statusLabel.setText(message);
    }
}
