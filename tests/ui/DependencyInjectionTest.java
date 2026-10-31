package tests.ui;

import app.contracts.AuthenticationService.*;
import app.contracts.SessionService.AuthenticatedUser;
import app.models.User;
import app.validation.LoginValidator;
import app.contracts.*;
import app.controllers.AuthenticationController;
import app.controllers.HeaderController;
import app.providers.AppServiceProvider;
import app.contracts.RegistrationService.*;
import app.validation.RegistrationValidator;
import app.errors.DuplicateAccountException;
import app.ui.FormFeedback;
import core.container.ServiceContainer;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** Проверяет FXML, внедрение зависимостей и обработку асинхронных результатов входа. */
public final class DependencyInjectionTest extends Application {
    private static volatile Throwable failure;
    private final AtomicReference<Navigator.Page> page = new AtomicReference<>();
    private final FakeSessions sessions = new FakeSessions();
    private final FakeAuthentication authentication = new FakeAuthentication();
    private final FakeRegistration registration = new FakeRegistration();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void main(String[] args) {
        launch(args);
        if (failure != null) { throw new AssertionError("FXML tests failed", failure); }
    }

    @Override
    public void start(Stage stage) {
        Platform.setImplicitExit(false);
        Thread.ofPlatform().start(() -> {
            try { runChecks(); }
            catch (Throwable error) { failure = error; }
            finally { executor.shutdownNow(); Platform.exit(); }
        });
    }

    private void runChecks() throws Exception {
        fx(() -> { checkTemplateFeedback(); return null; });
        ServiceContainer controllers = new ServiceContainer();
        controllers.instance(AuthenticationService.class, authentication);
        controllers.instance(RegistrationService.class, registration);
        controllers.instance(SessionService.class, sessions);
        controllers.instance(Executor.class, executor);
        controllers.instance(Navigator.class, page::set);
        AppServiceProvider.registerControllers(controllers);

        FXMLLoader login = fx(() -> load("login", controllers));
        fx(() -> {
            FXMLLoader second = load("login", controllers);
            check(login.getController() != second.getController(), "New controller for every page");
            check(service(login) == authentication && service(second) == authentication, "Service injected through contract");
            checkHeader(login, "REGISTER");
            ((Button) login.getNamespace().get("headerRegisterButton")).fire();
            check(page.get() == Navigator.Page.REGISTER, "Register navigation");
            FXMLLoader register = load("register", controllers);
            checkHeader(register, "LOGIN");
            ((Button) register.getNamespace().get("headerLoginButton")).fire();
            check(page.get() == Navigator.Page.LOGIN, "Return to login");
            return null;
        });
        submit(login, new AuthenticationResult.InvalidInput(Map.of("login", "Введите логин.")), false);
        check(fx(() -> feedback(login)).equals("Введите логин."), "Validation feedback shown");
        fx(() -> {
            check(invalid(login, "authLoginFeald"), "Login validation highlights field");
            int calls = authentication.calls;
            ((TextField) login.getNamespace().get("authLoginFeald")).setText("");
            check(feedback(login).isEmpty() && !invalid(login, "authLoginFeald"), "Editing clears login error even if still invalid");
            check(authentication.calls == calls, "Editing does not authenticate");
            return null;
        });
        submit(login, new AuthenticationResult.InvalidInput(Map.of("login", "Введите логин.", "password", "Введите пароль.")), false);
        fx(() -> {
            check(invalid(login, "authLoginFeald") && invalid(login, "authPassFeald"), "Next validation restores errors");
            ((TextField) login.getNamespace().get("authLoginFeald")).setText("Bob");
            check(feedback(login).equals("Введите пароль.") && invalid(login, "authPassFeald"), "Unedited password retains its error");
            ((PasswordField) login.getNamespace().get("authPassFeald")).setText("x");
            check(feedback(login).isEmpty() && !invalid(login, "authPassFeald"), "Password edit clears remaining feedback");
            return null;
        });
        submit(login, new AuthenticationResult.InvalidCredentials(), false);
        check(fx(() -> feedback(login)).equals("Неверный логин или пароль."), "Credential feedback shown");
        fx(() -> {
            ((PasswordField) login.getNamespace().get("authPassFeald")).setText("changed");
            check(feedback(login).isEmpty(), "Editing clears general credential error");
            return null;
        });
        submit(login, new AuthenticationResult.Throttled(java.time.Instant.now()), false);
        check(fx(() -> feedback(login)).contains("Слишком много"), "Throttle feedback shown");
        submit(login, null, true);
        check(fx(() -> feedback(login)).contains("Не удалось"), "Infrastructure error shown");
        submit(login, new AuthenticationResult.Success(sessions.user), false);
        check(page.get() == Navigator.Page.WELCOME, "Success navigates to welcome");
        check(authentication.offFx, "Authentication runs outside FX thread");
        check(authentication.login.equals("Alice") && authentication.password.equals(" pass "), "Credentials copied from controls");
        try { authentication.request.copyPassword(); throw new AssertionError("Request should be cleared"); }
        catch (IllegalStateException expected) { }

        checkRegistration(controllers);

        CountDownLatch loggedOut = new CountDownLatch(1);
        fx(() -> {
            FXMLLoader welcome = load("welcome", controllers);
            checkHeader(welcome, null);
            check(((Label) welcome.getNamespace().get("greetingLabel")).getText().contains("Alice"), "User greeting");
            sessions.latch = loggedOut;
            ((Button) welcome.getNamespace().get("logoutButton")).fire();
            return null;
        });
        check(loggedOut.await(5, TimeUnit.SECONDS), "Logout executed");
        // Task completion reaches FX asynchronously after the service returns.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (page.get() != Navigator.Page.LOGIN && System.nanoTime() < deadline) { Thread.sleep(10); }
        check(page.get() == Navigator.Page.LOGIN && sessions.current().isEmpty(), "Logout clears session and navigates");
        System.out.println("FXML dependency injection, authentication and registration checks passed");
    }

    /** Проверяет декларации на форме без контроллера и без известных приложению имён полей. */
    private static void checkTemplateFeedback() throws Exception {
        String template = """
                <?import javafx.scene.control.*?>
                <?import javafx.scene.layout.*?>
                <AnchorPane xmlns:fx="http://javafx.com/fxml/1">
                    <VBox>
                        <TextField fx:id="first">
                            <properties validationField="nickname" />
                        </TextField>
                        <TextField fx:id="second">
                            <properties validationField="confirmation" validationDependsOn="nickname" />
                        </TextField>
                        <Label fx:id="feedbackLabel">
                            <properties validationFeedback="true" />
                        </Label>
                    </VBox>
                </AnchorPane>
                """;
        FXMLLoader first = new FXMLLoader();
        javafx.scene.Parent root = first.load(new java.io.ByteArrayInputStream(
                template.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        FXMLLoader other = new FXMLLoader();
        javafx.scene.Parent otherRoot = other.load(new java.io.ByteArrayInputStream(
                template.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Map<String, String> errors = Map.of("nickname", "First error", "confirmation", "Dependent error");
        FormFeedback.showValidation(root, errors);
        FormFeedback.showValidation(otherRoot, errors);
        check(invalid(first, "first") && invalid(first, "second"), "FXML metadata discovers nested fields without a controller");
        ((TextField) first.getNamespace().get("first")).setText("edited");
        check(feedback(first).isEmpty() && !invalid(first, "second"), "FXML dependency clears related error");
        check(invalid(other, "first") && !feedback(other).isEmpty(), "Feedback state belongs to each form instance");
        FormFeedback.showValidation(root, errors);
        ((TextField) first.getNamespace().get("second")).setText("edited");
        check(feedback(first).equals("First error") && invalid(first, "first"), "Dependency is directional");
    }

    private void checkRegistration(ServiceContainer controllers) throws Exception {
        FXMLLoader form = fx(() -> load("register", controllers));
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put("login", "Логин: 3–128 символов, латинские буквы, цифры, точка, дефис или подчёркивание.");
        errors.put("email", "Введите корректную почту, например name@example.com.");
        errors.put("password", "Пароль должен содержать от 15 до 128 символов.");
        errors.put("passwordConfirmation", "Пароли не совпадают.");
        submitRegistration(form, new RegistrationResult.InvalidInput(errors), false);
        fx(() -> {
            check(feedback(form).equals(String.join("\n", errors.values())), "Registration displays every error in order");
            check(((TextField) form.getNamespace().get("registerEmailField")).getPseudoClassStates()
                    .contains(javafx.css.PseudoClass.getPseudoClass("invalid")), "Invalid field highlighted");
            snapshot(form, ".deps/registration-validation.png");
            int calls = registration.calls;
            ((TextField) form.getNamespace().get("registerEmailField")).setText("still-invalid");
            check(!invalid(form, "registerEmailField") && !feedback(form).contains(errors.get("email")), "Email edit clears only email error");
            check(invalid(form, "registerLoginField") && feedback(form).contains(errors.get("login")), "Other registration errors remain");
            ((TextField) form.getNamespace().get("registerEmailField")).setText(" Alice@Example.TEST ");
            check(!invalid(form, "registerEmailField"), "Returning to rejected value does not revalidate");
            ((PasswordField) form.getNamespace().get("registerPasswordConfirmField")).setText("x");
            check(!invalid(form, "registerPasswordConfirmField") && invalid(form, "registerPasswordField"), "Confirmation edit keeps password's own error");
            ((TextField) form.getNamespace().get("registerLoginField")).setText("changed");
            ((PasswordField) form.getNamespace().get("registerPasswordField")).setText("x");
            check(feedback(form).isEmpty() && !invalid(form, "registerPasswordField"), "Editing all fields clears feedback");
            check(registration.calls == calls, "Editing does not register or revalidate");
            return null;
        });
        submitRegistration(form, new RegistrationResult.InvalidInput(errors), false);
        fx(() -> {
            check(invalid(form, "registerPasswordConfirmField"), "Resubmission restores confirmation error");
            ((PasswordField) form.getNamespace().get("registerPasswordField")).setText("changed");
            check(!invalid(form, "registerPasswordField") && !invalid(form, "registerPasswordConfirmField"),
                    "Password edit clears dependent confirmation error");
            check(!feedback(form).contains(errors.get("passwordConfirmation")), "Stale mismatch message removed");
            return null;
        });
        submitRegistration(form, new RegistrationResult.InvalidInput(Map.of("email", "Эта почта уже зарегистрирована.")), false);
        check(fx(() -> feedback(form)).contains("уже зарегистрирована"), "Duplicate email shown");
        fx(() -> {
            ((TextField) form.getNamespace().get("registerEmailField")).setText("new@example.test");
            check(feedback(form).isEmpty() && !invalid(form, "registerEmailField"), "Duplicate error clears on edit");
            return null;
        });
        submitRegistration(form, null, true);
        check(fx(() -> feedback(form)).contains("Не удалось"), "Registration failure shown");
        check(registration.offFx, "Registration runs outside FX thread");
        check(registration.login.equals("Alice") && registration.email.equals("alice@example.test")
                && registration.password.equals(" correct horse battery ") && registration.confirmation.equals(registration.password),
                "All four fields reach service with password spaces preserved");
        try { registration.request.copyConfirmation(); throw new AssertionError("Request must be cleared after failure"); }
        catch (IllegalStateException expected) { }

        submitRegistration(form, new RegistrationResult.Success(42), false);
        int calls = registration.calls;
        fx(() -> {
            check(feedback(form).contains("Аккаунт создан"), "Registration success shown");
            Button button = (Button) form.getNamespace().get("registerButton");
            check(button.isDisabled(), "Successful form cannot resubmit");
            button.fire();
            check(registration.calls == calls, "No duplicate submission after success");
            snapshot(form, ".deps/registration-success.png");
            ((Button) form.getNamespace().get("headerLoginButton")).fire();
            check(page.get() == Navigator.Page.LOGIN, "Successful registration offers login");
            return null;
        });

        ServiceContainer rejected = new ServiceContainer();
        rejected.instance(RegistrationService.class, registration);
        rejected.instance(Navigator.class, page::set);
        rejected.instance(Executor.class, task -> { throw new RejectedExecutionException("Test saturation"); });
        AppServiceProvider.registerControllers(rejected);
        fx(() -> {
            FXMLLoader busy = load("register", rejected);
            fillRegistration(busy);
            ((Button) busy.getNamespace().get("registerButton")).fire();
            check(feedback(busy).contains("занято"), "Executor saturation shown");
            check(!((Button) busy.getNamespace().get("registerButton")).isDisabled(), "Saturation unlocks form");
            return null;
        });
    }

    private void submitRegistration(FXMLLoader form, RegistrationResult result, boolean fail) throws Exception {
        registration.result = result;
        registration.fail = fail;
        CountDownLatch completed = new CountDownLatch(1);
        fx(() -> {
            Button header = (Button) form.getNamespace().get("headerLoginButton");
            header.disabledProperty().addListener((observable, old, disabled) -> {
                if (!disabled) { completed.countDown(); }
            });
            fillRegistration(form);
            Button submit = (Button) form.getNamespace().get("registerButton");
            submit.fire();
            submit.fire();
            check(submit.isDisabled() && header.isDisabled(), "Form and navigation locked during registration");
            check(((PasswordField) form.getNamespace().get("registerPasswordField")).getText().equals(" correct horse battery ")
                    && ((PasswordField) form.getNamespace().get("registerPasswordConfirmField")).getText().equals(" correct horse battery "),
                    "Password fields retained while submitting");
            return null;
        });
        check(completed.await(5, TimeUnit.SECONDS), "Registration completion reaches FX");
        fx(() -> null);
    }

    private void fillRegistration(FXMLLoader form) {
        ((TextField) form.getNamespace().get("registerLoginField")).setText(" Alice ");
        ((TextField) form.getNamespace().get("registerEmailField")).setText(" Alice@Example.TEST ");
        ((PasswordField) form.getNamespace().get("registerPasswordField")).setText(" correct horse battery ");
        ((PasswordField) form.getNamespace().get("registerPasswordConfirmField")).setText(" correct horse battery ");
    }

    private static void snapshot(FXMLLoader loader, String file) throws Exception {
        javafx.scene.Parent root = loader.getRoot();
        if (root.getScene() == null) { new javafx.scene.Scene(root, 700, 640); }
        root.applyCss();
        root.layout();
        var image = root.snapshot(null, null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        var buffered = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) { buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y)); }
        }
        javax.imageio.ImageIO.write(buffered, "png", java.nio.file.Path.of(file).toFile());
    }

    private void submit(FXMLLoader loader, AuthenticationResult result, boolean fail) throws Exception {
        authentication.result = result;
        authentication.fail = fail;
        CountDownLatch completed = new CountDownLatch(1);
        fx(() -> {
            TextField login = (TextField) loader.getNamespace().get("authLoginFeald");
            PasswordField password = (PasswordField) loader.getNamespace().get("authPassFeald");
            Button button = (Button) loader.getNamespace().get("authanticate");
            button.disabledProperty().addListener((observable, old, disabled) -> {
                if (!disabled) { completed.countDown(); }
            });
            login.setText("Alice");
            password.setText(" pass ");
            button.fire();
            check(button.isDisabled(), "Submit disabled during request");
            check(password.getText().equals(" pass "), "Password retained while submitting");
            return null;
        });
        check(completed.await(5, TimeUnit.SECONDS), "UI receives task result");
        fx(() -> null); // Wait for the rest of the completion handler.
    }

    private static String feedback(FXMLLoader loader) {
        return ((Label) loader.getNamespace().get("feedbackLabel")).getText();
    }

    private static boolean invalid(FXMLLoader loader, String field) {
        return ((TextInputControl) loader.getNamespace().get(field)).getPseudoClassStates()
                .contains(javafx.css.PseudoClass.getPseudoClass("invalid"));
    }

    private static FXMLLoader load(String page, ServiceContainer controllers) throws Exception {
        FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                DependencyInjectionTest.class.getResource("/fxml/pages/" + page + ".fxml")));
        loader.setControllerFactory(controllers::resolve);
        loader.load();
        return loader;
    }

    private static AuthenticationService service(FXMLLoader loader) throws Exception {
        var field = AuthenticationController.class.getDeclaredField("authenticationService");
        field.setAccessible(true);
        return (AuthenticationService) field.get(loader.getController());
    }

    private static void checkHeader(FXMLLoader loader, String caption) throws Exception {
        Object header = loader.getNamespace().get("headerController");
        check(header instanceof HeaderController, "Included header controller created");
        var field = HeaderController.class.getDeclaredField("actionContainer");
        field.setAccessible(true);
        StackPane container = (StackPane) field.get(header);
        if (caption == null) {
            check(container.getChildren().isEmpty(), "Welcome header has no button");
        } else {
            check(container.getChildren().size() == 1 && container.getChildren().getFirst() instanceof Button button
                    && button.getText().equals(caption), "FXML injection precedes initialize");
        }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> future = new FutureTask<>(work);
        Platform.runLater(future);
        return future.get(10, TimeUnit.SECONDS);
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    private static final class FakeAuthentication implements AuthenticationService {
        int calls;
        AuthenticationResult result;
        boolean fail;
        boolean offFx;
        String login;
        String password;
        LoginRequest request;
        public AuthenticationResult authenticate(LoginRequest request) throws IOException {
            calls++;
            this.request = request;
            offFx = !Platform.isFxApplicationThread();
            login = request.login();
            password = new String(request.copyPassword());
            if (fail) { throw new IOException("Test failure"); }
            return result;
        }
    }

    private static final class FakeSessions implements SessionService {
        volatile AuthenticatedUser user = new AuthenticatedUser(1, "Alice", "alice@example.test");
        CountDownLatch latch;
        public void start(User credentials) { user = AuthenticatedUser.from(credentials); }
        public Optional<AuthenticatedUser> restore() { return current(); }
        public Optional<AuthenticatedUser> current() { return Optional.ofNullable(user); }
        public void logout() {
            check(!Platform.isFxApplicationThread(), "Logout runs in background");
            user = null;
            latch.countDown();
        }
    }

    private static final class FakeRegistration implements RegistrationService {
        RegistrationResult result;
        boolean fail;
        boolean offFx;
        int calls;
        String login;
        String email;
        String password;
        String confirmation;
        RegistrationRequest request;

        public RegistrationResult register(RegistrationRequest request) {
            calls++;
            this.request = request;
            offFx = !Platform.isFxApplicationThread();
            login = request.login();
            email = request.email();
            password = new String(request.copyPassword());
            confirmation = new String(request.copyConfirmation());
            if (fail) { throw new IllegalStateException("Test failure"); }
            return result;
        }
    }
}
