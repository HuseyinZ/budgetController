package UI.View;

import UI.ProfitPanel;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

public class ProfitView extends JFrame {
    public ProfitView(AppState appState) {
        super("Kar Panosu");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(new ProfitPanel(Objects.requireNonNull(appState, "appState")), BorderLayout.CENTER);
        setSize(600, 360);
        setLocationRelativeTo(null);
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
