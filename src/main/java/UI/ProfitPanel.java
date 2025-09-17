package UI;

import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.Date;
import java.util.Objects;

public class ProfitPanel extends JPanel {
    private final AppState appState;
    private final JSpinner dailyDateSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JSpinner monthSpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getMonthValue(), 1, 12, 1));
    private final JSpinner yearSpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getYear(), 2000, 2100, 1));
    private final JLabel dailySalesLabel = new JLabel("0.00");
    private final JLabel dailyExpenseLabel = new JLabel("0.00");
    private final JLabel dailyNetLabel = new JLabel("0.00");
    private final JLabel monthlySalesLabel = new JLabel("0.00");
    private final JLabel monthlyExpenseLabel = new JLabel("0.00");
    private final JLabel monthlyNetLabel = new JLabel("0.00");
    private final PropertyChangeListener listener = this::handleStateChange;

    public ProfitPanel(AppState appState) {
        this.appState = Objects.requireNonNull(appState, "appState");
        setLayout(new BorderLayout(8, 8));
        add(buildContent(), BorderLayout.CENTER);
        appState.addPropertyChangeListener(listener);
        updateDailyTotals();
        updateMonthlyTotals();
    }

    private JComponent buildContent() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(8, 8, 8, 8);
        gc.anchor = GridBagConstraints.WEST;

        int row = 0;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Günlük tarih"), gc);
        dailyDateSpinner.setEditor(new JSpinner.DateEditor(dailyDateSpinner, "yyyy-MM-dd"));
        gc.gridx = 1;
        panel.add(dailyDateSpinner, gc);
        JButton refreshDaily = new JButton("Güncelle");
        refreshDaily.addActionListener(e -> updateDailyTotals());
        gc.gridx = 2;
        panel.add(refreshDaily, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Günlük Satış"), gc);
        gc.gridx = 1;
        panel.add(dailySalesLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Günlük Gider"), gc);
        gc.gridx = 1;
        panel.add(dailyExpenseLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Günlük Net"), gc);
        dailyNetLabel.setFont(dailyNetLabel.getFont().deriveFont(Font.BOLD));
        gc.gridx = 1;
        panel.add(dailyNetLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Ay / Yıl"), gc);
        JPanel monthPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        monthPanel.add(monthSpinner);
        monthPanel.add(yearSpinner);
        gc.gridx = 1;
        panel.add(monthPanel, gc);
        JButton refreshMonthly = new JButton("Güncelle");
        refreshMonthly.addActionListener(e -> updateMonthlyTotals());
        gc.gridx = 2;
        panel.add(refreshMonthly, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Aylık Satış"), gc);
        gc.gridx = 1;
        panel.add(monthlySalesLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Aylık Gider"), gc);
        gc.gridx = 1;
        panel.add(monthlyExpenseLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        panel.add(new JLabel("Aylık Net"), gc);
        monthlyNetLabel.setFont(monthlyNetLabel.getFont().deriveFont(Font.BOLD));
        gc.gridx = 1;
        panel.add(monthlyNetLabel, gc);

        return panel;
    }

    private void handleStateChange(PropertyChangeEvent event) {
        if (AppState.EVENT_SALES.equals(event.getPropertyName()) || AppState.EVENT_EXPENSES.equals(event.getPropertyName())) {
            SwingUtilities.invokeLater(() -> {
                updateDailyTotals();
                updateMonthlyTotals();
            });
        }
    }

    private void updateDailyTotals() {
        LocalDate date = convertToDate(dailyDateSpinner);
        BigDecimal sales = appState.getSalesTotal(date);
        BigDecimal expenses = appState.getExpenseTotal(date);
        BigDecimal net = appState.getNetProfit(date);
        dailySalesLabel.setText(format(sales));
        dailyExpenseLabel.setText(format(expenses));
        dailyNetLabel.setText(format(net));
        dailyNetLabel.setForeground(net.signum() >= 0 ? new Color(46, 125, 50) : Color.RED.darker());
    }

    private void updateMonthlyTotals() {
        YearMonth month = YearMonth.of((int) yearSpinner.getValue(), (int) monthSpinner.getValue());
        BigDecimal sales = appState.getSalesTotal(month);
        BigDecimal expenses = appState.getExpenseTotal(month);
        BigDecimal net = appState.getNetProfit(month);
        monthlySalesLabel.setText(format(sales));
        monthlyExpenseLabel.setText(format(expenses));
        monthlyNetLabel.setText(format(net));
        monthlyNetLabel.setForeground(net.signum() >= 0 ? new Color(46, 125, 50) : Color.RED.darker());
    }

    private String format(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private LocalDate convertToDate(JSpinner spinner) {
        Date date = (Date) spinner.getValue();
        return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        appState.removePropertyChangeListener(listener);
    }
}
