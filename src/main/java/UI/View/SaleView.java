package UI.View;

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
    private final JSpinner dailySpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, java.util.Calendar.DAY_OF_MONTH));
    private final JSpinner monthSpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getMonthValue(), 1, 12, 1));
    private final JSpinner yearSpinner = new JSpinner(new SpinnerNumberModel(LocalDate.now().getYear(), 2000, 2100, 1));
    private final JLabel dailyLabel = new JLabel("0.00");
    private final JLabel monthlyLabel = new JLabel("0.00");

    public SaleView() {
        this(AppState.getInstance());
    }

    public SaleView(AppState appState) {
        super("Satış Özeti");
        this.appState = Objects.requireNonNull(appState, "appState");
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(8, 8, 8, 8);
        gc.anchor = GridBagConstraints.WEST;

        dailySpinner.setEditor(new JSpinner.DateEditor(dailySpinner, "yyyy-MM-dd"));

        int row = 0;
        gc.gridx = 0; gc.gridy = row;
        add(new JLabel("Günlük tarih"), gc);
        gc.gridx = 1;
        add(dailySpinner, gc);
        JButton refreshDaily = new JButton("Günlük güncelle");
        refreshDaily.addActionListener(e -> updateDaily());
        gc.gridx = 2;
        add(refreshDaily, gc);
        gc.gridx = 3;
        add(dailyLabel, gc);

        row++;
        gc.gridx = 0; gc.gridy = row;
        add(new JLabel("Ay / Yıl"), gc);
        JPanel monthPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        monthPanel.add(monthSpinner);
        monthPanel.add(yearSpinner);
        gc.gridx = 1;
        add(monthPanel, gc);
        JButton refreshMonthly = new JButton("Aylık güncelle");
        refreshMonthly.addActionListener(e -> updateMonthly());
        gc.gridx = 2;
        add(refreshMonthly, gc);
        gc.gridx = 3;
        add(monthlyLabel, gc);

        updateDaily();
        updateMonthly();
        pack();
        setLocationRelativeTo(null);
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
