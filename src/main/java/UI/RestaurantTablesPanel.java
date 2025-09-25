package UI;

import state.AppState;
import state.TableOrderStatus;
import state.TableSnapshot;

import model.Role;
import model.User;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class RestaurantTablesPanel extends JPanel {
    private final AppState appState;
    private final User currentUser;
    private final Map<Integer, JButton> tableButtons = new HashMap<>();
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(new Locale("tr", "TR"));
    private final PropertyChangeListener listener = this::handleStateChange;

    public RestaurantTablesPanel(AppState appState, User currentUser) {
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        buildLayout();
        appState.addPropertyChangeListener(listener);
    }

    private void buildLayout() {
        Map<String, List<AppState.AreaDefinition>> byBuilding = appState.getAreas().stream()
                .collect(Collectors.groupingBy(
                        AppState.AreaDefinition::getBuilding,
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)));

        byBuilding.forEach((building, areas) -> {
            areas.sort(Comparator.comparing(AppState.AreaDefinition::getSection));
            JPanel buildingPanel = new JPanel();
            buildingPanel.setLayout(new BoxLayout(buildingPanel, BoxLayout.Y_AXIS));
            buildingPanel.setBorder(BorderFactory.createTitledBorder(building));
            buildingPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

            for (AppState.AreaDefinition area : areas) {
                JPanel sectionPanel = new JPanel(new BorderLayout());
                sectionPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.BLACK), area.getSection(), TitledBorder.LEFT, TitledBorder.TOP));
                sectionPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

                JPanel grid = new JPanel(new GridLayout(2, 5, 8, 8));
                grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
                List<Integer> tableNumbers = new ArrayList<>(area.getTableNumbers());
                Collections.sort(tableNumbers);
                for (Integer tableNo : tableNumbers) {
                    JButton button = createTableButton(tableNo);
                    grid.add(button);
                }
                sectionPanel.add(grid, BorderLayout.CENTER);
                buildingPanel.add(sectionPanel);
                buildingPanel.add(Box.createVerticalStrut(10));
            }

            add(buildingPanel);
            add(Box.createVerticalStrut(15));
        });
    }

    private JButton createTableButton(int tableNo) {
        JButton button = new JButton();
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBackground(Color.RED);
        button.addActionListener(e -> openTableDialog(tableNo));
        tableButtons.put(tableNo, button);
        refreshButton(tableNo);
        return button;
    }

    private void openTableDialog(int tableNo) {
        TableSnapshot snapshot = appState.snapshot(tableNo);
        TableOrderDialog dialog = new TableOrderDialog(
                SwingUtilities.getWindowAncestor(this), appState, snapshot, currentUser
        );
        dialog.setVisible(true);
    }


    private void handleStateChange(PropertyChangeEvent event) {
        if (!AppState.EVENT_TABLES.equals(event.getPropertyName())) {
            return;
        }
        Object newValue = event.getNewValue();
        if (newValue instanceof Integer) {
            int tableNo = (Integer) newValue;
            SwingUtilities.invokeLater(() -> refreshButton(tableNo));
        }
    }

    private void refreshButton(int tableNo) {
        JButton button = tableButtons.get(tableNo);
        if (button == null) return;

        TableSnapshot snapshot = appState.snapshot(tableNo);
        button.setText(formatText(snapshot));

        // ️ RENKLER buradan geliyor
        button.setBackground(colorFor(snapshot.getStatus()));  // sadece background
        button.setForeground(Color.DARK_GRAY);                 // hep koyu gri yazı

        button.setBorder(BorderFactory.createLineBorder(Color.BLACK));
    }



    private String formatText(TableSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("Masa ").append(snapshot.getTableNo());
        BigDecimal total = snapshot.getTotal();
        if (total != null && total.compareTo(BigDecimal.ZERO) > 0) {
            sb.append("<br/><b>").append(currencyFormat.format(total)).append("</b>");
        }
        return "<html><center>" + sb + "</center></html>";
    }

    private Color colorFor(TableOrderStatus status) {
        if (status == null) {
            return Color.WHITE;
        }
        return switch (status) {
            case EMPTY   -> new Color(224, 244, 239);
            case ORDERED -> new Color(255, 245, 157);
            case SERVED  -> new Color(165, 214, 167);
        };
    }


    public boolean canPerformSale() {
        Role role = currentUser.getRole();
        return role == Role.ADMIN || role == Role.KASIYER;
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        appState.removePropertyChangeListener(listener);
    }
}
