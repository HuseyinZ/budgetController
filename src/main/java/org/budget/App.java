package org.budget;

import UI.View.AdminView;
import UI.View.AllSalesView;
import UI.View.ExpensesView;
import UI.View.LoginView;
import UI.View.ProfitView;
import UI.View.RestraurantTablesView;
import UI.View.SaleView;
import UI.View.SettingView;
import UI.View.WaiterView;
import state.AppState;
import com.formdev.flatlaf.FlatLightLaf;
import model.Role;
import model.User;
import org.mindrot.jbcrypt.BCrypt;
import service.UserService;

import javax.swing.*;

public class App {
    public static void main(String[] args) {


        UserService userService = new UserService();

        boolean created = userService.seedAdminIfNotExists();
        if (created) {
            System.out.println("✔ Admin oluşturuldu: username=admin, password=1234");
        } else {
            System.out.println("ℹ Admin zaten var, oluşturma atlandı.");
        }


        SwingUtilities.invokeLater(() -> {
            FlatLightLaf.setup();
            LoginView loginView = new LoginView();
            loginView.setLoginListener(App::onLogin);
            loginView.open();
        });

    }

    private static void onLogin(User user) {
        Role role = user.getRole();

        if (role == Role.ADMIN) {
            new SaleView().open();
            new AllSalesView().open();
            new AdminView().open();
            new SettingView().open();
            return;
        }

        if (role == Role.KASIYER) {
            new AllSalesView().open();

        AppState state = AppState.getInstance();
        Role role = user.getRole();

        switch (role) {
            case GARSON -> new WaiterView(state, user).open();
            default -> new RestraurantTablesView(state, user).open();
        }

        if (role == Role.ADMIN) {
            new AdminView().open();
            new SettingView().open();
            new SaleView(state).open();
            new AllSalesView(state).open();
            new ProfitView(state).open();
            new ExpensesView(state, user).open();
        } else if (role == Role.KASIYER) {
            new SaleView(state).open();
            new ExpensesView(state, user).open();
        }
    }
}
