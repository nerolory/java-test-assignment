package app.contracts;

import app.contracts.SessionService.AuthenticatedUser;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
/** Проверка данных входа и создание сессии. */
@FunctionalInterface
public interface AuthenticationService {
    /**
     * Выполняет вход и очищает пароль запроса при любом исходе.
     * @param request данные формы; после вызова повторное использование запрещено
     * @return результат проверки
     * @throws IOException если сессию нельзя безопасно сохранить
     */
    AuthenticationResult authenticate(LoginRequest request) throws IOException;

    /** Данные одной попытки входа. close очищает внутреннюю копию пароля. */
    public final class LoginRequest implements AutoCloseable {
        private final String login;
        private final char[] password;
        private boolean closed;

        public LoginRequest(String login, char[] password) {
            this.login = Objects.requireNonNull(login).strip();
            this.password = Objects.requireNonNull(password).clone();
        }

        public String login() {
            return login;
        }

        public char[] copyPassword() {
            if (closed) {
                throw new IllegalStateException("Login request is closed");
            }
            return password.clone();
        }

        @Override
        public void close() {
            Arrays.fill(password, '\0');
            closed = true;
        }
    }

    /** Результат входа. Ошибки инфраструктуры передаются исключениями. */
    public sealed interface AuthenticationResult {
        record Success(AuthenticatedUser user) implements AuthenticationResult { }

        record InvalidInput(Map<String, String> errors) implements AuthenticationResult {
            public InvalidInput {
                errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
            }
        }

        record InvalidCredentials() implements AuthenticationResult { }

        /** Лимит попыток исчерпан; пароль не проверялся. */
        record Throttled(Instant retryAt) implements AuthenticationResult { }
    }
}
