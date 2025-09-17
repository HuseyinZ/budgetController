package UI.View;

import UI.AdminPanel;
import UI.AllSalesPanel;
import UI.ExpensesPanel;
import UI.ProfitPanel;
import UI.RestaurantTablesPanel;
import model.Role;
import model.User;
import state.AppState;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class DashboardView extends JFrame {
    private static final String CARD_FLOORS = "floors";
    private static final String CARD_USERS = "users";
    private static final String CARD_EXPENSES = "expenses";
    private static final String CARD_SALES = "sales";
    private static final String CARD_PROFIT = "profit";

    private final AppState appState;
    private final User currentUser;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel contentPanel = new JPanel(cardLayout);

    public DashboardView(AppState appState, User user) {
        super("Budget Controller");
        this.appState = Objects.requireNonNull(appState, "appState");
        this.currentUser = Objects.requireNonNull(user, "user");

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        contentPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel toolbar = buildToolbar();
        add(toolbar, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);

        setSize(1024, 700);
        setLocationRelativeTo(null);
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        toolbar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        ButtonGroup group = new ButtonGroup();
        Role role = currentUser.getRole();

        List<CardConfig> configs = new ArrayList<>();
        configs.add(new CardConfig(CARD_FLOORS, "Katlar", () -> new JScrollPane(new RestaurantTablesPanel(appState, currentUser)), r -> true));
        configs.add(new CardConfig(CARD_USERS, "Kullanıcı İşlemleri", AdminPanel::new, r -> r == Role.ADMIN));
        configs.add(new CardConfig(CARD_EXPENSES, "Giderler", () -> new ExpensesPanel(appState, currentUser), r -> r == Role.ADMIN || r == Role.KASIYER));
        configs.add(new CardConfig(CARD_SALES, "Satışlar", () -> new AllSalesPanel(appState), r -> r == Role.ADMIN || r == Role.KASIYER));
        configs.add(new CardConfig(CARD_PROFIT, "Net Kar", () -> new ProfitPanel(appState), r -> r == Role.ADMIN));

        String initialCard = null;

        for (CardConfig config : configs) {
            if (!config.visible().test(role)) {
                continue;
            }

            JComponent component = config.component().get();
            contentPanel.add(component, config.card());

            JToggleButton button = new JToggleButton(config.label());
            button.addActionListener(e -> cardLayout.show(contentPanel, config.card()));
            toolbar.add(button);
            group.add(button);

            if (initialCard == null) {
                initialCard = config.card();
                button.setSelected(true);
                cardLayout.show(contentPanel, initialCard);
            }
        }

        if (initialCard == null) {
            JLabel empty = new JLabel("Bu kullanıcı için yetkili modül bulunamadı");
            empty.setHorizontalAlignment(SwingConstants.CENTER);
            contentPanel.add(empty, "empty");
            cardLayout.show(contentPanel, "empty");
        }

        return toolbar;
    }

    public void open() {
        SwingUtilities.invokeLater(() -> setVisible(true));
    }

    private record CardConfig(String card, String label, Supplier<JComponent> component, Predicate<Role> visible) {
    }
}
