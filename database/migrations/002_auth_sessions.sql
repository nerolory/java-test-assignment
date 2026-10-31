CREATE TABLE IF NOT EXISTS auth_sessions (
    id INTEGER PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE CHECK (length(token_hash) = 64),
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_fingerprint TEXT NOT NULL CHECK (length(credential_fingerprint) = 64),
    expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS auth_sessions_user_id ON auth_sessions(user_id);
CREATE INDEX IF NOT EXISTS auth_sessions_expires_at ON auth_sessions(expires_at);
