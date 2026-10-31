package app.contracts;

import app.models.User;
import java.io.IOException;
import java.util.Optional;
/** Состояние входа и сохранение сессии между запусками приложения. */
public interface SessionService {
    /**
     * Сохраняет сессию после успешной проверки пароля.
     * @param verifiedUser проверенные учётные данные
     * @throws IOException если защищённый токен не удалось сохранить
     */
    void start(User verifiedUser) throws IOException;

    /**
     * Проверяет сохранённый токен и загружает текущего пользователя.
     * @return пользователь или пустой результат для отсутствующей/недействительной сессии
     * @throws IOException если токен нельзя прочитать, расшифровать или удалить
     */
    Optional<AuthenticatedUser> restore() throws IOException;

    /**
     * Возвращает пользователя из памяти с проверкой срока сессии.
     * @return текущий пользователь; повторного обращения в БД нет
     */
    Optional<AuthenticatedUser> current();

    /**
     * Отзывает токен в БД, очищает память и удаляет локальный файл.
     * @throws IOException если файл нельзя удалить; токен в БД к этому моменту уже отозван
     */
    void logout() throws IOException;

    /** Данные вошедшего пользователя без пароля и хеша. */
    public record AuthenticatedUser(long id, String login, String email) {
        /** Возвращает снимок для интерфейса, исключая пароль и хеш. */
        public static AuthenticatedUser from(User user) {
            return new AuthenticatedUser(user.getId(), user.getLogin(), user.getEmail());
        }
    }
}
