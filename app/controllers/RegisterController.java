package app.controllers;

import app.contracts.Controllable;
import app.contracts.Navigator;
import app.contracts.RegistrationService;
import app.contracts.RegistrationService.RegistrationRequest;
import app.contracts.RegistrationService.RegistrationResult;
import app.ui.FormFeedback;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.Parent;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

/** Передаёт форму сервису регистрации и отображает результат. */
public final class RegisterController implements Controllable {
    private static final System.Logger LOG = System.getLogger(RegisterController.class.getName());
    private final RegistrationService registrationService;
    private final Executor executor;
    private final Navigator navigator;
    private boolean registered;

    public RegisterController(RegistrationService registrationService, Executor executor, Navigator navigator) {
        this.registrationService = registrationService;
        this.executor = executor;
        this.navigator = navigator;
    }

    @FXML
    private TextField registerLoginField;

    @FXML
    private TextField registerEmailField;

    @FXML
    private PasswordField registerPasswordField;

    @FXML
    private PasswordField registerPasswordConfirmField;

    @FXML
    private Button registerButton;

    @FXML
    private HeaderController headerController;

    @FXML
    private Button headerLoginButton;

    @FXML
    private Parent form;

    @FXML
    @Override
    public void initialize() {
        headerController.setActionButton(headerLoginButton);
        headerLoginButton.setOnAction(event -> navigator.show(Navigator.Page.LOGIN));
        registerButton.setDefaultButton(true);
        registerButton.setOnAction(event -> register());
    }

    private void register() {
        if (registered) {
            return;
        }
        RegistrationRequest request = readRequest();
        FormFeedback.clear(form);
        setBusy(true);

        Task<RegistrationResult> task = new Task<>() {
            @Override
            protected RegistrationResult call() {
                try (request) {
                    return registrationService.register(request);
                }
            }
        };
        task.setOnSucceeded(event -> {
            setBusy(false);
            showResult(task.getValue());
        });
        task.setOnFailed(event -> {
            setBusy(false);
            LOG.log(System.Logger.Level.ERROR, "Registration failed", task.getException());
            FormFeedback.showError(form, "Не удалось создать аккаунт. Повторите попытку.");
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

    /** Копирует поля в запрос; при ошибке введённые значения остаются в форме. */
    private RegistrationRequest readRequest() {
        char[] password = registerPasswordField.getText().toCharArray();
        char[] confirmation = registerPasswordConfirmField.getText().toCharArray();
        try {
            return new RegistrationRequest(registerLoginField.getText(), registerEmailField.getText(),
                    password, confirmation);
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }

    private void showResult(RegistrationResult result) {
        switch (result) {
            case RegistrationResult.Success ignored -> {
                registerPasswordField.clear();
                registerPasswordConfirmField.clear();
                registered = true;
                setBusy(false);
                FormFeedback.showSuccess(form, "Аккаунт создан. Нажмите LOGIN в шапке, чтобы войти.");
                headerLoginButton.requestFocus();
            }
            case RegistrationResult.InvalidInput invalid -> FormFeedback.showValidation(form, invalid.errors());
        }
    }

    private void setBusy(boolean busy) {
        registerButton.setDisable(busy || registered);
        registerLoginField.setDisable(busy || registered);
        registerEmailField.setDisable(busy || registered);
        registerPasswordField.setDisable(busy || registered);
        registerPasswordConfirmField.setDisable(busy || registered);
        headerLoginButton.setDisable(busy);
    }
}
