package UI.View;

import UI.SettingsPanel;

import javax.swing.*;
import java.awt.*;

public class SettingView extends JFrame {
    public SettingView() {
        super("Veritabanı Ayarları");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(new SettingsPanel(), BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(null);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
