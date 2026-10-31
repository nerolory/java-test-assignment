ALTER TABLE users ADD COLUMN version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0);

DROP TRIGGER IF EXISTS users_set_updated_at;

CREATE TRIGGER users_set_updated_at
AFTER UPDATE OF login, email, password_hash ON users
FOR EACH ROW
WHEN NEW.login IS NOT OLD.login
  OR NEW.email IS NOT OLD.email
  OR NEW.password_hash IS NOT OLD.password_hash
BEGIN
    UPDATE users SET updated_at = CURRENT_TIMESTAMP, version = OLD.version + 1 WHERE id = NEW.id;
END;
