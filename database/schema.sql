CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY,
    login TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK (length(trim(login)) > 0),
    email TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK (length(trim(email)) > 0),
    password_hash TEXT NOT NULL CHECK (length(password_hash) > 0),
    version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER IF NOT EXISTS users_set_updated_at
AFTER UPDATE OF login, email, password_hash ON users
FOR EACH ROW
WHEN NEW.login IS NOT OLD.login
  OR NEW.email IS NOT OLD.email
  OR NEW.password_hash IS NOT OLD.password_hash
BEGIN
    UPDATE users SET updated_at = CURRENT_TIMESTAMP, version = OLD.version + 1 WHERE id = NEW.id;
END;

CREATE TABLE IF NOT EXISTS auth_sessions (
    id INTEGER PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE CHECK (length(token_hash) = 64),
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_fingerprint TEXT NOT NULL CHECK (length(credential_fingerprint) = 64),
    expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS auth_sessions_user_id ON auth_sessions(user_id);
CREATE INDEX IF NOT EXISTS auth_sessions_expires_at ON auth_sessions(expires_at);
