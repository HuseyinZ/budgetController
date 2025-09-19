package UI.View;

import UI.ProductEditDialog;
import model.Role;
import model.User;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

public class SaleView extends JFrame {
    private final AppState appState;
    private final User currentUser;
    private final JSpinner dailySpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JSpinner monthSpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getMonthValue(), 1, 12, 1));
    private final JSpinner yearSpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getYear(), 2000, 2100, 1));
    private final JLabel dailyLabel = new JLabel("0.00");
    private final JLabel monthlyLabel = new JLabel("0.00");

    public SaleView() {
        this(AppState.getInstance(), null);
    }

    public SaleView(AppState appState) {
        this(appState, null);
    }

    public SaleView(AppState appState, User currentUser) {
        super("Satış Özeti");
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = currentUser;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildContentPanel(), BorderLayout.CENTER);

        updateDaily();
        updateMonthly();
        pack();
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

    private JComponent buildContentPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(8, 8, 8, 8);
        gc.anchor = GridBagConstraints.WEST;

        dailySpinner.setEditor(new JSpinner.DateEditor(dailySpinner, "yyyy-MM-dd"));

        int row = 0;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Günlük tarih"), gc);
        gc.gridx = 1;
        panel.add(dailySpinner, gc);
        JButton refreshDaily = new JButton("Günlük güncelle");
        refreshDaily.addActionListener(e -> updateDaily());
        gc.gridx = 2;
        panel.add(refreshDaily, gc);
        gc.gridx = 3;
        panel.add(dailyLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Ay / Yıl"), gc);
        JPanel monthPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        monthPanel.add(monthSpinner);
        monthPanel.add(yearSpinner);
        gc.gridx = 1;
        panel.add(monthPanel, gc);
        JButton refreshMonthly = new JButton("Aylık güncelle");
        refreshMonthly.addActionListener(e -> updateMonthly());
        gc.gridx = 2;
        panel.add(refreshMonthly, gc);
        gc.gridx = 3;
        panel.add(monthlyLabel, gc);

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

    private void updateDaily() {
        Date date = (Date) dailySpinner.getValue();
        LocalDate local = Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
        double total = appState.getSalesTotal(local).doubleValue();
        dailyLabel.setText(String.format(Locale.getDefault(), "%.2f", total));
    }

    private void updateMonthly() {
        int month = (int) monthSpinner.getValue();
        int year = (int) yearSpinner.getValue();
        YearMonth ym = YearMonth.of(year, month);
        double total = appState.getSalesTotal(ym).doubleValue();
        monthlyLabel.setText(String.format(Locale.getDefault(), "%.2f", total));
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }
}
