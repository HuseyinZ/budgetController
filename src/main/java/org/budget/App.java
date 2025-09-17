package org.budget;

import UI.View.LoginView;
import UI.View.DashboardView;
import com.formdev.flatlaf.FlatLightLaf;
import model.User;
import service.UserService;
import state.AppState;

import javax.swing.*;
import java.util.Objects;

public class App {
    private static final AppState APP_STATE = AppState.getInstance();


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
        User authenticated = Objects.requireNonNull(user, "user");
        DashboardView dashboard = new DashboardView(APP_STATE, authenticated);
        dashboard.open();

        AppState state = AppState.getInstance();
        new DashboardView(state, user).open();
    }
}
