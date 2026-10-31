package app.controllers;

import app.contracts.Controllable;
import app.contracts.Navigator;
import app.contracts.SessionService;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

/** Приветствие текущего пользователя и завершение сессии. */
public final class WelcomeController implements Controllable {
    private static final System.Logger LOG = System.getLogger(WelcomeController.class.getName());
    private final SessionService sessions;
    private final Executor executor;
    private final Navigator navigator;

    @FXML private Label greetingLabel;
    @FXML private Label feedbackLabel;
    @FXML private Button logoutButton;

    public WelcomeController(SessionService sessions, Executor executor, Navigator navigator) {
        this.sessions = sessions;
        this.executor = executor;
        this.navigator = navigator;
    }

    @FXML
    @Override
    public void initialize() {
        sessions.current().ifPresent(user -> greetingLabel.setText("Добро пожаловать, " + user.login() + "!"));
        logoutButton.setOnAction(event -> logout());
    }

    private void logout() {
        logoutButton.setDisable(true);
        feedbackLabel.setText("");
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                sessions.logout();
                return null;
            }
        };
        task.setOnSucceeded(event -> navigator.show(Navigator.Page.LOGIN));
        task.setOnFailed(event -> {
            LOG.log(System.Logger.Level.ERROR, "Logout failed", task.getException());
            logoutButton.setDisable(false);
            feedbackLabel.setText("Не удалось завершить сессию. Повторите попытку.");
        });
        try {
            executor.execute(task);
        } catch (RejectedExecutionException error) {
            logoutButton.setDisable(false);
            feedbackLabel.setText("Приложение занято. Повторите попытку позже.");
        }
    }
}
