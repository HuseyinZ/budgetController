package UI.View;

import UI.AdminPanel;
import UI.ProductEditDialog;
import model.Role;
import model.User;
import service.UserService;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class AdminView extends JFrame {
    private final UserService userService;
    private final AppState appState;
    private final User currentUser;

    public AdminView() {
        this(new UserService(), AppState.getInstance(), null);
    }

    public AdminView(UserService userService) {
        this(userService, AppState.getInstance(), null);
    }

    public AdminView(UserService userService, User currentUser) {
        this(userService, AppState.getInstance(), currentUser);
    }

    public AdminView(UserService userService, AppState appState, User currentUser) {
        super("Kullanıcı Yönetimi");
        this.userService = Objects.requireNonNull(userService, "userService");
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = currentUser;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(buildToolbar(), BorderLayout.NORTH);
        add(new AdminPanel(this.userService, currentUser), BorderLayout.CENTER);
        setSize(720, 480);
        setLocationRelativeTo(null);
    }

    private JComponent buildToolbar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        JButton manageButton = new JButton("Ürün Yönetimi");
        manageButton.addActionListener(e -> openProductManagement());
        manageButton.setVisible(canManageProducts());
        panel.add(manageButton);
        return panel;
    }

    private boolean canManageProducts() {
        if (currentUser == null || currentUser.getRole() == null) {
            return false;
        }
        return currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.KASIYER;
    }

    private void openProductManagement() {
        ProductEditDialog dialog = new ProductEditDialog(this, appState, currentUser);
        dialog.setVisible(true);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
