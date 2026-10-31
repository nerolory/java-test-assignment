package app.services;

import app.contracts.RegistrationRepository;
import app.contracts.RegistrationService;
import app.errors.DuplicateAccountException;
import app.contracts.RegistrationService.RegistrationRequest;
import app.contracts.RegistrationService.RegistrationResult;
import app.validation.RegistrationValidator;
import core.contracts.PasswordHasher;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Валидация, проверка уникальности, хеширование и создание пользователя. */
public final class DefaultRegistrationService implements RegistrationService {
    private final RegistrationRepository users;
    private final PasswordHasher passwords;
    private final RegistrationValidator validator;

    public DefaultRegistrationService(RegistrationRepository users, PasswordHasher passwords,
            RegistrationValidator validator) {
        this.users = users;
        this.passwords = passwords;
        this.validator = validator;
    }

    @Override
    public RegistrationResult register(RegistrationRequest request) {
        try (request) {
            char[] password = request.copyPassword();
            char[] confirmation = request.copyConfirmation();
            try {
                Map<String, String> errors = validator.validate(request.login(), request.email(), password, confirmation);
                if (!errors.isEmpty()) {
                    return new RegistrationResult.InvalidInput(errors);
                }
                Set<String> conflicts = users.findConflicts(request.login(), request.email());
                if (!conflicts.isEmpty()) {
                    return conflictResult(conflicts);
                }

                // Дорогой расчёт выполняется до INSERT, без удержания соединения с БД.
                String hash = passwords.hash(password);
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Registration cancelled");
                }
                try {
                    long id = users.create(request.login(), request.email(), hash);
                    return new RegistrationResult.Success(id);
                } catch (DuplicateAccountException conflict) {
                    return conflictResult(conflict.fields());
                }
            } finally {
                Arrays.fill(password, '\0');
                Arrays.fill(confirmation, '\0');
            }
        }
    }

    private RegistrationResult.InvalidInput conflictResult(Set<String> fields) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (fields.contains("login")) {
            errors.put("login", "Этот логин уже занят.");
        }
        if (fields.contains("email")) {
            errors.put("email", "Эта почта уже зарегистрирована.");
        }
        return new RegistrationResult.InvalidInput(errors);
    }
}
