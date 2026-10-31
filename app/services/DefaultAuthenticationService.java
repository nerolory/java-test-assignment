package app.services;

import app.contracts.AuthenticationService;
import app.contracts.AuthenticationRepository;
import app.contracts.SessionService;
import app.contracts.AuthenticationService.AuthenticationResult;
import app.contracts.AuthenticationService.LoginRequest;
import app.validation.LoginValidator;
import app.contracts.SessionService.AuthenticatedUser;
import core.contracts.PasswordHasher;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Проверка формы, пароля и создание сессии. Не зависит от JavaFX. */
public final class DefaultAuthenticationService implements AuthenticationService {
    private final AuthenticationRepository users;
    private final PasswordHasher passwords;
    private final SessionService sessions;
    private final LoginValidator validator;
    private final LoginAttemptLimiter limiter;
    private final String dummyHash;

    public DefaultAuthenticationService(AuthenticationRepository users, PasswordHasher passwords,
            SessionService sessions, LoginValidator validator, LoginAttemptLimiter limiter) {
        this.users = users;
        this.passwords = passwords;
        this.sessions = sessions;
        this.validator = validator;
        this.limiter = limiter;
        // Неизвестный логин также проходит дорогую проверку хеша.
        char[] dummy = java.util.UUID.randomUUID().toString().toCharArray();
        try {
            dummyHash = passwords.hash(dummy);
        } finally {
            Arrays.fill(dummy, '\0');
        }
    }

    @Override
    public AuthenticationResult authenticate(LoginRequest request) throws IOException {
        try (request) {
            char[] password = request.copyPassword();
            try {
                var errors = validator.validate(request.login(), password);
                if (!errors.isEmpty()) {
                    return new AuthenticationResult.InvalidInput(errors);
                }
                // Запрос к БД идёт до учёта попытки: сбой БД не расходует лимит.
                var candidate = users.findByLogin(request.login());
                // Попытка учитывается до дорогой проверки хеша, чтобы параллельные запросы не обходили лимит.
                var retryAt = limiter.reserve(request.login());
                if (retryAt.isPresent()) {
                    return new AuthenticationResult.Throttled(retryAt.get());
                }
                boolean valid = passwords.verify(password, candidate.map(user -> user.getPasswordHash()).orElse(dummyHash));
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Authentication cancelled");
                }
                if (candidate.isEmpty() || !valid) {
                    return new AuthenticationResult.InvalidCredentials();
                }
                limiter.succeeded(request.login());
                sessions.start(candidate.get());
                return new AuthenticationResult.Success(AuthenticatedUser.from(candidate.get()));
            } finally {
                Arrays.fill(password, '\0');
            }
        }
    }
}
