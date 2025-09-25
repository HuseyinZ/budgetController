package org.budget;

import UI.View.LoginView;
import UI.View.DashboardView;
import com.formdev.flatlaf.FlatLightLaf;
import model.User;
import org.jetbrains.annotations.NotNull;
import service.UserService;
import state.AppState;

import javax.swing.*;
import java.util.Objects;

public class App {
    private static final AppState APP_STATE = AppState.getInstance();


    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {
            FlatLightLaf.setup();
            LoginView loginView = new LoginView();
            loginView.setLoginListener(App::onLogin);
            loginView.open();
        });
    }

    private static void onLogin(@NotNull User user) {
        User authenticated = Objects.requireNonNull(user, "user");
        DashboardView dashboard = new DashboardView(APP_STATE, authenticated);
        dashboard.open();
    }
}
