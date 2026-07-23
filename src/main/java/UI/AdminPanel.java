package UI;

import model.Role;
import model.User;
import service.UserService;
import state.AppState;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Locale;

public class AdminPanel extends JPanel {
    private final UserService userService;
    private final User currentUser;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton deleteButton = new JButton("Kullanıcı sil");

    public AdminPanel() {
        this(new UserService(), null);
    }

    public AdminPanel(UserService userService) {
        this(userService, null);
    }

    public AdminPanel(User currentUser) {
        this(new UserService(), currentUser);
    }

    public AdminPanel(UserService userService, User currentUser) {
        this.userService = Objects.requireNonNull(userService, "userService");
        this.currentUser = currentUser;
        setLayout(new BorderLayout(8, 8));

        tableModel = new DefaultTableModel(new Object[]{"ID", "Kullanıcı", "Ad Soyad", "Rol", "Aktif"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> updateDeleteButtonState());
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING));
        JButton refreshButton = new JButton("Yenile");
        JButton createUserButton = new JButton("Yeni Kullanıcı");
        JButton toggleActiveButton = new JButton("Aktif/Pasif");
        JButton resetPasswordButton = new JButton("Parola Sıfırla");
        JButton areaPermsButton = new JButton("Alan Yetkileri");
        JButton kitchenRoutingButton = new JButton("Mutfak Eşleştirme");
        buttons.add(refreshButton);
        buttons.add(createUserButton);
        buttons.add(toggleActiveButton);
        buttons.add(resetPasswordButton);
        buttons.add(areaPermsButton);
        buttons.add(kitchenRoutingButton);
        deleteButton.setEnabled(false);
        buttons.add(deleteButton);
        add(buttons, BorderLayout.NORTH);
        areaPermsButton.addActionListener(e -> openAreaPermissionsDialog());
        kitchenRoutingButton.addActionListener(e -> openKitchenRoutingDialog());

        statusLabel.setForeground(Color.DARK_GRAY);
        add(statusLabel, BorderLayout.SOUTH);

        refreshButton.addActionListener(e -> refreshUsers());
        createUserButton.addActionListener(e -> showCreateUserDialog());
        toggleActiveButton.addActionListener(e -> toggleSelectedUser());
        resetPasswordButton.addActionListener(e -> resetPassword());
        deleteButton.addActionListener(e -> deleteSelectedUser());

        refreshUsers();
    }

    private void refreshUsers() {
        tableModel.setRowCount(0);
        List<User> allUsers = userService.getAllUsers();
        int shown = 0;
        for (User user : allUsers) {
            // ADMIN kullanıcılar listede asla görünmez — sadece DB/kod ile yönetilir.
            // Bu sayede admin yetkisi UI'dan kaldırılamaz veya silinemez.
            if (user.getRole() == Role.ADMIN) {
                continue;
            }
            tableModel.addRow(new Object[]{
                    user.getId(),
                    user.getUsername(),
                    user.getFullName(),
                    user.getRole(),
                    user.isActive() ? "Evet" : "Hayır"
            });
            shown++;
        }
        if (shown == 0) {
            statusLabel.setText("Henüz kasiyer/garson kullanıcı yok");
        } else {
            statusLabel.setText(shown + " kullanıcı yüklendi");
        }
        updateDeleteButtonState();
    }

    private void openAreaPermissionsDialog() {
        int row = table.getSelectedRow();
        if (row < 0) {
            showMessage("Önce bir kullanıcı seçin", true);
            return;
        }
        Long id = (Long) tableModel.getValueAt(row, 0);
        Object roleObj = tableModel.getValueAt(row, 3);
        Role userRole = (roleObj instanceof Role) ? (Role) roleObj
                : Role.valueOf(String.valueOf(roleObj));
        if (userRole != Role.GARSON) {
            JOptionPane.showMessageDialog(this,
                    "Alan yetkileri sadece GARSON rolündeki kullanıcılar için anlamlıdır.\n"
                  + "ADMIN ve KASIYER tüm alanları görür.",
                    "Bilgi", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        User target = userService.getUserById(id).orElse(null);
        if (target == null) {
            showMessage("Kullanıcı bulunamadı", true);
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        new UserAreaPermissionsDialog(owner, AppState.getInstance(), target).setVisible(true);
    }

    private void openKitchenRoutingDialog() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        new CategoryPrinterRoutingDialog(owner, AppState.getInstance()).setVisible(true);
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

    private void deleteSelectedUser() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            showMessage("Silmek için kullanıcı seçin", true);
            return;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        Long id = (Long) tableModel.getValueAt(modelRow, 0);
        String username = (String) tableModel.getValueAt(modelRow, 1);
        int confirm = JOptionPane.showConfirmDialog(
                this,
                username + " kullanıcısını silmek istiyor musunuz?",
                "Onay",
                JOptionPane.YES_NO_OPTION
        );
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            userService.deleteUserById(id, currentUser);
            showMessage("Kullanıcı silindi", false);
            refreshUsers();
            table.clearSelection();
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            if (message == null || message.isBlank()) {
                message = "Kullanıcı silinemedi";
            }
            showMessage(message, true);
        } finally {
            updateDeleteButtonState();
        }
    }

    private void updateDeleteButtonState() {
        deleteButton.setEnabled(table.getSelectedRow() >= 0);
    }

    private void showCreateUserDialog() {
        JTextField usernameField = new JTextField(15);
        JTextField fullNameField = new JTextField(15);
        // ADMIN UI'dan oluşturulamaz — sadece KASIYER ve GARSON seçilebilir.
        // Yeni admin gerekirse DB'den veya kod tarafından ekle.
        Role[] uiRoles = Arrays.stream(Role.values())
                .filter(r -> r != Role.ADMIN)
                .toArray(Role[]::new);
        JComboBox<Role> roleComboBox = new JComboBox<>(uiRoles);
        roleComboBox.setSelectedItem(Role.KASIYER);
        JPasswordField passwordField = new JPasswordField(15);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;

        addFormRow(form, gbc, 0, "Kullanıcı Adı", usernameField);
        addFormRow(form, gbc, 1, "Ad Soyad", fullNameField);
        addFormRow(form, gbc, 2, "Rol", roleComboBox);
        addFormRow(form, gbc, 3, "İlk Parola", passwordField);

        int result = JOptionPane.showConfirmDialog(
                this,
                form,
                "Yeni Kullanıcı",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );

        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        String username = usernameField.getText().trim();
        String fullName = fullNameField.getText().trim();
        Role role = (Role) roleComboBox.getSelectedItem();
        char[] passwordChars = passwordField.getPassword();
        String password = passwordChars != null ? new String(passwordChars).trim() : "";

        try {
            if (username.isBlank()) {
                showMessage("Kullanıcı adı zorunludur", true);
                return;
            }
            if (password.isBlank()) {
                showMessage("Parola zorunludur", true);
                return;
            }
            if (role == null) {
                showMessage("Lütfen bir rol seçin", true);
                return;
            }

            try {
                userService.createUser(username, password, role, fullName);
                showMessage("Kullanıcı başarıyla oluşturuldu", false);
                refreshUsers();
            } catch (RuntimeException ex) {
                showMessage(resolveCreateUserError(ex), true);
            }
        } finally {
            if (passwordChars != null) {
                Arrays.fill(passwordChars, '\0');        // veya: Arrays.fill(passwordChars, (char) 0);
            }
            passwordField.setText("");
        }
    }

    private static void addFormRow(JPanel form, GridBagConstraints gbc, int row, String labelText,
                                   Component component) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        form.add(new JLabel(labelText), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        form.add(component, gbc);
    }

    private String resolveCreateUserError(RuntimeException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("duplicate") || normalized.contains("unique")) {
                    return "Kullanıcı adı zaten mevcut";
                }
            }
        }
        return "Kullanıcı oluşturulamadı. Lütfen bilgileri kontrol edin";
    }

    private void showMessage(String message, boolean error) {
        statusLabel.setForeground(error ? Color.RED.darker() : new Color(0, 128, 0));
        statusLabel.setText(message);
    }
}
