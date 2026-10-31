package app.errors;

import java.util.Set;

/** Конфликт уникальности при сохранении: login, email или оба поля. */
public final class DuplicateAccountException extends RuntimeException {
    private final Set<String> fields;

    public DuplicateAccountException(Set<String> fields, Throwable cause) {
        super("Account identifiers already exist", cause);
        this.fields = Set.copyOf(fields);
    }

    public Set<String> fields() { return fields; }
}
