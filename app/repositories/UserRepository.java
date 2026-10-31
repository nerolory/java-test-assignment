package app.repositories;

import app.contracts.AuthenticationRepository;
import app.contracts.RegistrationRepository;
import app.errors.DuplicateAccountException;
import app.models.User;
import core.database.Database;
import core.errors.UniqueConstraintException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Работа с пользователями через модель User. Каждый сервис получает нужный ему контракт. */
public final class UserRepository implements AuthenticationRepository, RegistrationRepository {
    private final Database database;

    public UserRepository(Database database) {
        this.database = database;
    }

    @Override
    public Optional<User> findByLogin(String login) {
        return User.on(database).where("login", login).first();
    }

    @Override
    public Optional<User> findById(long id) {
        return User.find(database, id);
    }

    @Override
    public Set<String> findConflicts(String login, String email) {
        Set<String> fields = new LinkedHashSet<>();
        if (User.on(database).where("login", login).exists()) {
            fields.add("login");
        }
        if (User.on(database).where("email", email).exists()) {
            fields.add("email");
        }
        return Set.copyOf(fields);
    }

    @Override
    public long create(String login, String email, String passwordHash) {
        User user = new User(database);
        user.setLogin(login);
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        try {
            user.save();
            return user.getId();
        } catch (UniqueConstraintException error) {
            // Другой запрос мог занять данные после предварительной проверки.
            Set<String> fields = findConflicts(login, email);
            if (!fields.isEmpty()) {
                throw new DuplicateAccountException(fields, error);
            }
            throw error;
        }
    }
}
