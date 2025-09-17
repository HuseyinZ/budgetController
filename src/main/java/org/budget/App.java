package org.budget;

import UI.View.AdminView;
import UI.View.AllSalesView;
import UI.View.LoginView;
import UI.View.SaleView;
import UI.View.SettingView;
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
        new SaleView().open();
       new AllSalesView().open();
        if (user.getRole() == Role.ADMIN) {
            new AdminView().open();
            new SettingView().open();
        }
    }
}
