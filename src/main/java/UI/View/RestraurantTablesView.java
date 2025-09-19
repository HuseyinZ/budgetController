package UI.View;

import UI.ProductEditDialog;
import UI.RestaurantTablesPanel;
import model.User;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class RestraurantTablesView extends JFrame {
    private final AppState appState;
    private final User currentUser;

    public RestraurantTablesView(AppState appState, User user) {
        super("Restoran Masaları");
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = Objects.requireNonNull(user, "user");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(buildHeader(), BorderLayout.NORTH);
        RestaurantTablesPanel panel = new RestaurantTablesPanel(this.appState, this.currentUser);
        add(new JScrollPane(panel), BorderLayout.CENTER);
        setSize(900, 600);
        setLocationRelativeTo(null);
    }

    private JComponent buildHeader() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Restoran Masaları");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        panel.add(title, BorderLayout.WEST);
        if (currentUser.getRole() == model.Role.ADMIN || currentUser.getRole() == model.Role.KASIYER) {
            JButton manageButton = new JButton("Ürün ekle/güncelle");
            manageButton.addActionListener(e -> openProductEditor());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(manageButton);
            panel.add(buttons, BorderLayout.EAST);
        }
        panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        return panel;
    }

    private void openProductEditor() {
        ProductEditDialog dialog = new ProductEditDialog(this, appState, currentUser);
        dialog.setVisible(true);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
