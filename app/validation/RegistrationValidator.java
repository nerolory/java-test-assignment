package app.validation;

import app.validation.CredentialRules;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Правила формы регистрации, независимые от интерфейса и БД. */
public final class RegistrationValidator {
    private static final String EMAIL_ATOM = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";
    private static final Pattern EMAIL_LOCAL = Pattern.compile(EMAIL_ATOM + "(?:\\." + EMAIL_ATOM + ")*");
    private static final Pattern DOMAIN_LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    /**
     * Проверяет все поля за один вызов. Пароль не обрезается и не меняет регистр.
     * @param login нормализованный логин
     * @param email нормализованная почта
     * @param password пароль
     * @param confirmation повтор пароля
     * @return ошибки в порядке полей формы
     */
    public Map<String, String> validate(String login, String email, char[] password, char[] confirmation) {
        Map<String, String> errors = new LinkedHashMap<>();
        CredentialRules.loginError(login).ifPresent(message -> errors.put("login", message));

        if (email.isBlank()) {
            errors.put("email", "Введите почту.");
        } else if (!validEmail(email)) {
            errors.put("email", "Введите корректную почту, например name@example.com.");
        }

        CredentialRules.passwordError(password).ifPresent(message -> errors.put("password", message));

        if (confirmation.length == 0) {
            errors.put("passwordConfirmation", "Повторите пароль.");
        } else if (!Arrays.equals(password, confirmation)) {
            errors.put("passwordConfirmation", "Пароли не совпадают.");
        }
        return errors;
    }

    /** Принимает обычные ASCII-адреса и домены, включая записанные в Punycode. */
    private boolean validEmail(String email) {
        if (email.length() > 254) {
            return false;
        }
        int at = email.indexOf('@');
        if (at < 1 || at > 64 || at != email.lastIndexOf('@')) {
            return false;
        }
        if (!EMAIL_LOCAL.matcher(email.substring(0, at)).matches()) {
            return false;
        }
        String[] labels = email.substring(at + 1).split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!DOMAIN_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

}
