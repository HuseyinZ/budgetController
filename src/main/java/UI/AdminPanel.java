package UI;

import model.User;
import service.UserService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.Objects;

public class AdminPanel extends JPanel {
    private final UserService userService;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JLabel statusLabel = new JLabel(" ");

    public AdminPanel() {
        this(new UserService());
    }

    public AdminPanel(UserService userService) {
        this.userService = Objects.requireNonNull(userService, "userService");
        setLayout(new BorderLayout(8, 8));

        tableModel = new DefaultTableModel(new Object[]{"ID", "Kullanıcı", "Ad Soyad", "Rol", "Aktif"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING));
        JButton refreshButton = new JButton("Yenile");
        JButton toggleActiveButton = new JButton("Aktif/Pasif");
        JButton resetPasswordButton = new JButton("Parola Sıfırla");
        buttons.add(refreshButton);
        buttons.add(toggleActiveButton);
        buttons.add(resetPasswordButton);
        add(buttons, BorderLayout.NORTH);

        statusLabel.setForeground(Color.DARK_GRAY);
        add(statusLabel, BorderLayout.SOUTH);

        refreshButton.addActionListener(e -> refreshUsers());
        toggleActiveButton.addActionListener(e -> toggleSelectedUser());
        resetPasswordButton.addActionListener(e -> resetPassword());

        refreshUsers();
    }

    private void refreshUsers() {
        tableModel.setRowCount(0);
        List<User> users = userService.getAllUsers();
        for (User user : users) {
            tableModel.addRow(new Object[]{
                    user.getId(),
                    user.getUsername(),
                    user.getFullName(),
                    user.getRole(),
                    user.isActive() ? "Evet" : "Hayır"
            });
        }
        statusLabel.setText(users.isEmpty() ? "Henüz kullanıcı yok" : users.size() + " kullanıcı yüklendi");
    }

    private void toggleSelectedUser() {
        int row = table.getSelectedRow();
        if (row < 0) {
            showMessage("Lütfen bir kullanıcı seçin", true);
            return;
        }
        Long id = (Long) tableModel.getValueAt(row, 0);
        boolean active = "Evet".equals(tableModel.getValueAt(row, 4));
        if (active) {
            userService.deactivate(id);
            showMessage("Kullanıcı pasif hale getirildi", false);
        } else {
            userService.activate(id);
            showMessage("Kullanıcı aktifleştirildi", false);
        }
        refreshUsers();
    }

    private void resetPassword() {
        int row = table.getSelectedRow();
        if (row < 0) {
            showMessage("Parola sıfırlamak için kullanıcı seçin", true);
            return;
        }
        String newPassword = JOptionPane.showInputDialog(this, "Yeni parola", "Parola Sıfırlama", JOptionPane.PLAIN_MESSAGE);
        if (newPassword == null || newPassword.isBlank()) {
            showMessage("Parola değişmedi", true);
            return;
        }
        Long id = (Long) tableModel.getValueAt(row, 0);
        userService.changePassword(id, newPassword.trim());
        showMessage("Parola güncellendi", false);
    }

    private void showMessage(String message, boolean error) {
        statusLabel.setForeground(error ? Color.RED.darker() : new Color(0, 128, 0));
        statusLabel.setText(message);
    }
}
