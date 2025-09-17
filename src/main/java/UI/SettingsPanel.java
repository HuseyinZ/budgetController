package UI;

import DataConnection.Db;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

public class SettingsPanel extends JPanel {
    private final JTextField urlField = new JTextField(40);
    private final JTextField userField = new JTextField(20);
    private final JPasswordField passwordField = new JPasswordField(20);
    private final JTextField maxPoolField = new JTextField(5);
    private final JTextField minIdleField = new JTextField(5);
    private final JLabel statusLabel = new JLabel(" ");

    public SettingsPanel() {
        setLayout(new BorderLayout(8, 8));
        add(buildForm(), BorderLayout.CENTER);
        add(buildActions(), BorderLayout.SOUTH);
        loadCurrentConfig();
    }

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;

        int row = 0;
        gc.gridx = 0; gc.gridy = row;
        form.add(new JLabel("JDBC URL"), gc);
        gc.gridx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        form.add(urlField, gc);

        row++; gc.gridy = row; gc.gridx = 0; gc.fill = GridBagConstraints.NONE;
        form.add(new JLabel("Kullanıcı"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        form.add(userField, gc);

        row++; gc.gridy = row; gc.gridx = 0; gc.fill = GridBagConstraints.NONE;
        form.add(new JLabel("Parola"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        form.add(passwordField, gc);

        row++; gc.gridy = row; gc.gridx = 0; gc.fill = GridBagConstraints.NONE;
        form.add(new JLabel("Maksimum Havuz"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        form.add(maxPoolField, gc);

        row++; gc.gridy = row; gc.gridx = 0; gc.fill = GridBagConstraints.NONE;
        form.add(new JLabel("Minimum Bekleme"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
        form.add(minIdleField, gc);

        return form;
    }

    private JPanel buildActions() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING));
        JButton saveButton = new JButton("Kaydet");
        JButton reloadButton = new JButton("Yeniden Yükle");
        panel.add(saveButton);
        panel.add(reloadButton);
        panel.add(statusLabel);
        statusLabel.setForeground(Color.DARK_GRAY);

        saveButton.addActionListener(e -> saveConfig());
        reloadButton.addActionListener(e -> loadCurrentConfig());
        return panel;
    }

    private void loadCurrentConfig() {
        Properties props = Db.currentConfiguration();
        urlField.setText(props.getProperty("db.url", ""));
        userField.setText(props.getProperty("db.user", ""));
        passwordField.setText(props.getProperty("db.password", ""));
        maxPoolField.setText(props.getProperty("db.pool.maxSize", "10"));
        minIdleField.setText(props.getProperty("db.pool.minIdle", "2"));
        statusLabel.setText("Etkin yapılandırma yüklendi");
        statusLabel.setForeground(Color.DARK_GRAY);
    }

    private void saveConfig() {
        try {
            int maxPool = Integer.parseInt(maxPoolField.getText().trim());
            int minIdle = Integer.parseInt(minIdleField.getText().trim());
            if (maxPool <= 0 || minIdle < 0) {
                throw new IllegalArgumentException("Havuz değerleri pozitif olmalı");
            }
            Properties props = new Properties();
            props.setProperty("db.url", urlField.getText().trim());
            props.setProperty("db.user", userField.getText().trim());
            props.setProperty("db.password", new String(passwordField.getPassword()));
            props.setProperty("db.pool.maxSize", Integer.toString(maxPool));
            props.setProperty("db.pool.minIdle", Integer.toString(minIdle));

            Path target = Db.externalConfigPath();
            Files.createDirectories(Objects.requireNonNull(target.getParent(), "config dir"));
            try (OutputStream out = Files.newOutputStream(target)) {
                props.store(out, "BudgetController database configuration");
            }
            statusLabel.setText("Yapılandırma kaydedildi: " + target);
            statusLabel.setForeground(new Color(0, 128, 0));
        } catch (IllegalArgumentException ex) {
            statusLabel.setText(ex.getMessage());
            statusLabel.setForeground(Color.RED.darker());
        } catch (IOException ex) {
            statusLabel.setText("Kaydedilirken hata: " + ex.getMessage());
            statusLabel.setForeground(Color.RED.darker());
        }
    }
}
