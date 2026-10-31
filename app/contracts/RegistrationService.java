package app.contracts;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
/** Проверка формы и создание учётной записи. */
@FunctionalInterface
public interface RegistrationService {
    /**
     * Регистрирует пользователя без автоматического входа.
     * @param request данные формы; пароли очищаются при любом исходе
     * @return ID созданного пользователя или ошибки полей
     */
    RegistrationResult register(RegistrationRequest request);

    /** Данные регистрации. Пароли хранятся в отдельных массивах и очищаются после обработки. */
    public final class RegistrationRequest implements AutoCloseable {
        private final String login;
        private final String email;
        private final char[] password;
        private final char[] confirmation;
        private boolean closed;

        /**
         * Копирует пароли, убирает внешние пробелы у логина и почты.
         * @param login логин
         * @param email почта; приложение сравнивает адреса без учёта регистра
         * @param password пароль без нормализации
         * @param confirmation повтор пароля
         */
        public RegistrationRequest(String login, String email, char[] password, char[] confirmation) {
            this.login = Objects.requireNonNull(login).strip();
            this.email = Objects.requireNonNull(email).strip().toLowerCase(Locale.ROOT);
            this.password = Objects.requireNonNull(password).clone();
            this.confirmation = Objects.requireNonNull(confirmation).clone();
        }

        public String login() { return login; }

        public String email() { return email; }

        /** @return копия пароля, которую вызывающий код очищает после использования */
        public char[] copyPassword() {
            ensureOpen();
            return password.clone();
        }

        /** @return копия повтора пароля, которую вызывающий код очищает после использования */
        public char[] copyConfirmation() {
            ensureOpen();
            return confirmation.clone();
        }

        /** Очищает оба массива. Повторное закрытие допустимо. */
        @Override
        public void close() {
            Arrays.fill(password, '\0');
            Arrays.fill(confirmation, '\0');
            closed = true;
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("Registration request is closed");
            }
        }
    }

    /** Успешная регистрация или ошибки полей. Сбои инфраструктуры передаются исключениями. */
    public sealed interface RegistrationResult {
        record Success(long userId) implements RegistrationResult { }

        record InvalidInput(Map<String, String> errors) implements RegistrationResult {
            public InvalidInput {
                errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
            }
        }
    }
}
