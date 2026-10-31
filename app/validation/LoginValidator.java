package app.validation;

import app.validation.CredentialRules;
import java.util.LinkedHashMap;
import java.util.Map;

/** Проверка формы входа по общим с регистрацией правилам. */
public final class LoginValidator {
    public Map<String, String> validate(String login, char[] password) {
        Map<String, String> errors = new LinkedHashMap<>();
        CredentialRules.loginError(login).ifPresent(message -> errors.put("login", message));
        CredentialRules.passwordError(password).ifPresent(message -> errors.put("password", message));
        return errors;
    }
}
