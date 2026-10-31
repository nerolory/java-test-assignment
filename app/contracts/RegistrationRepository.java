package app.contracts;

import app.errors.DuplicateAccountException;
import java.util.Set;

/** Запросы, необходимые для регистрации. Открытый пароль в репозиторий не передаётся. */
public interface RegistrationRepository {
    /**
     * Предварительно проверяет уникальность логина и почты.
     * @param login логин
     * @param email почта
     * @return имена занятых полей: login, email
     */
    Set<String> findConflicts(String login, String email);

    /**
     * Создаёт пользователя с проверкой уникальности на уровне БД.
     * @param login логин
     * @param email почта
     * @param passwordHash готовый хеш пароля
     * @return ID созданной записи
     * @throws DuplicateAccountException если логин или почта уже заняты
     */
    long create(String login, String email, String passwordHash);
}
