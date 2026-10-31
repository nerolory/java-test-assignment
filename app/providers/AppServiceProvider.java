package app.providers;

import app.validation.LoginValidator;
import app.contracts.AuthenticationRepository;
import app.contracts.AuthenticationService;
import app.contracts.Navigator;
import app.contracts.SessionService;
import app.contracts.RegistrationRepository;
import app.contracts.RegistrationService;
import app.contracts.SessionRepository;
import app.repositories.DatabaseSessionRepository;
import app.controllers.AuthenticationController;
import app.controllers.HeaderController;
import app.controllers.RegisterController;
import app.controllers.WelcomeController;
import app.repositories.UserRepository;
import app.validation.RegistrationValidator;
import app.services.DefaultRegistrationService;
import app.services.DefaultAuthenticationService;
import app.services.LoginAttemptLimiter;
import app.services.PersistentSessionService;
import core.container.ServiceContainer;
import core.contracts.PasswordHasher;
import core.contracts.SecretStore;
import core.contracts.ServiceProvider;
import core.database.Database;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;

/** Регистрация сервисов приложения и зависимостей контроллеров. */
public final class AppServiceProvider implements ServiceProvider {
    /**
     * @param services контейнер с инфраструктурными зависимостями
     */
    @Override
    public void register(ServiceContainer services) {
        UserRepository repository = new UserRepository(services.resolve(Database.class));
        Clock clock = services.resolve(Clock.class);
        SessionRepository storedSessions = new DatabaseSessionRepository(services.resolve(Database.class));
        SessionService sessions = new PersistentSessionService(repository, storedSessions, services.resolve(SecretStore.class),
                clock, Duration.ofDays(30));
        services.instance(AuthenticationRepository.class, repository);
        services.instance(RegistrationRepository.class, repository);
        services.instance(SessionRepository.class, storedSessions);
        services.instance(SessionService.class, sessions);
        services.instance(AuthenticationService.class, new DefaultAuthenticationService(repository,
                services.resolve(PasswordHasher.class), sessions, new LoginValidator(), new LoginAttemptLimiter(clock)));
        services.instance(RegistrationService.class, new DefaultRegistrationService(repository,
                services.resolve(PasswordHasher.class), new RegistrationValidator()));
        registerControllers(services);
    }

    /** Регистрирует фабрики контроллеров; зависимости разрешаются при загрузке FXML. */
    public static void registerControllers(ServiceContainer services) {
        services.bind(AuthenticationController.class, () -> new AuthenticationController(
                services.resolve(AuthenticationService.class), services.resolve(Executor.class),
                services.resolve(Navigator.class)));
        services.bind(HeaderController.class, HeaderController::new);
        services.bind(RegisterController.class, () -> new RegisterController(services.resolve(RegistrationService.class),
                services.resolve(Executor.class), services.resolve(Navigator.class)));
        services.bind(WelcomeController.class, () -> new WelcomeController(services.resolve(SessionService.class),
                services.resolve(Executor.class), services.resolve(Navigator.class)));
    }
}
