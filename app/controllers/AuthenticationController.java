package app.controllers;

import app.contracts.AuthenticationService.AuthenticationResult;
import app.contracts.AuthenticationService.LoginRequest;
import app.contracts.AuthenticationService;
import app.contracts.Controllable;
import app.contracts.Navigator;
import app.ui.FormFeedback;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.Parent;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/** Читает форму и показывает результат входа. */
public final class AuthenticationController implements Controllable {
    private static final System.Logger LOG = System.getLogger(AuthenticationController.class.getName());
    private static final DateTimeFormatter RETRY_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final AuthenticationService authenticationService;
    private final Executor executor;
    private final Navigator navigator;

    @FXML private TextField authLoginFeald;
    @FXML private PasswordField authPassFeald;
    @FXML private Button authanticate;
    @FXML private Parent form;
    @FXML private HeaderController headerController;
    @FXML private Button headerRegisterButton;

    public AuthenticationController(AuthenticationService authenticationService, Executor executor,
            Navigator navigator) {
        this.authenticationService = authenticationService;
        this.executor = executor;
        this.navigator = navigator;
    }

    @FXML
    @Override
    public void initialize() {
        headerController.setTitle("TEST APP");
        headerController.setActionButton(headerRegisterButton);
        headerRegisterButton.setOnAction(event -> navigator.show(Navigator.Page.REGISTER));
        authanticate.setDefaultButton(true);
        authanticate.setOnAction(event -> authenticate());
    }

    private void authenticate() {
        char[] password = authPassFeald.getText().toCharArray();
        LoginRequest request = new LoginRequest(authLoginFeald.getText(), password);
        Arrays.fill(password, '\0');
        FormFeedback.clear(form);
        setBusy(true);

        Task<AuthenticationResult> task = new Task<>() {
            @Override
            protected AuthenticationResult call() throws Exception {
                try (request) {
                    return authenticationService.authenticate(request);
                }
            }
        };
        task.setOnSucceeded(event -> {
            setBusy(false);
            showResult(task.getValue());
        });
        task.setOnFailed(event -> {
            setBusy(false);
            LOG.log(System.Logger.Level.ERROR, "Authentication failed", task.getException());
            FormFeedback.showError(form, "Не удалось выполнить вход или сохранить сессию. Повторите попытку.");
        });
        task.setOnCancelled(event -> {
            request.close();
            setBusy(false);
        });
        try {
            executor.execute(task);
        } catch (RejectedExecutionException error) {
            request.close();
            setBusy(false);
            FormFeedback.showError(form, "Приложение занято. Повторите попытку позже.");
        }
    }

    private void showResult(AuthenticationResult result) {
        switch (result) {
            case AuthenticationResult.Success ignored -> {
                authPassFeald.clear();
                navigator.show(Navigator.Page.WELCOME);
            }
            case AuthenticationResult.InvalidInput invalid ->
                    FormFeedback.showValidation(form, invalid.errors());
            case AuthenticationResult.InvalidCredentials ignored ->
                    FormFeedback.showError(form, "Неверный логин или пароль.");
            case AuthenticationResult.Throttled throttled ->
                    FormFeedback.showError(form, "Слишком много попыток входа. Повторите после "
                            + RETRY_TIME.format(throttled.retryAt()) + ".");
        }
    }

    private void setBusy(boolean busy) {
        authanticate.setDisable(busy);
        headerRegisterButton.setDisable(busy);
        authLoginFeald.setDisable(busy);
        authPassFeald.setDisable(busy);
    }
}
