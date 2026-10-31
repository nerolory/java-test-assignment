package app.contracts;

import app.models.User;
import java.util.Optional;

/** Получение пользователя для проверки пароля и восстановления сессии. */
public interface AuthenticationRepository {
    Optional<User> findByLogin(String login);
    Optional<User> findById(long id);
}
