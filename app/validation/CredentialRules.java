package app.validation;

import java.util.Optional;
import java.util.regex.Pattern;

/** Общие правила логина и пароля для регистрации и входа. */
public final class CredentialRules {
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9_.-]{3,128}");

    private CredentialRules() { }

    /**
     * Проверяет логин после удаления внешних пробелов.
     * @param login логин
     * @return сообщение об ошибке или пустой результат
     */
    public static Optional<String> loginError(String login) {
        if (login.isBlank()) {
            return Optional.of("Введите логин.");
        }
        if (login.length() > 128 || !LOGIN.matcher(login).matches()) {
            return Optional.of("Логин: 3–128 символов, латинские буквы, цифры, точка, дефис или подчёркивание.");
        }
        return Optional.empty();
    }

    /**
     * Проверяет длину в кодовых точках Unicode, не изменяя пароль.
     * @param password пароль с сохранением пробелов и регистра
     * @return сообщение об ошибке или пустой результат
     */
    public static Optional<String> passwordError(char[] password) {
        int length = Character.codePointCount(password, 0, password.length);
        if (length == 0) {
            return Optional.of("Введите пароль.");
        }
        if (length < 15 || length > 128) {
            return Optional.of("Пароль должен содержать от 15 до 128 символов.");
        }
        for (char character : password) {
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) {
                return Optional.empty();
            }
        }
        return Optional.of("Пароль не должен состоять только из пробелов.");
    }
}
