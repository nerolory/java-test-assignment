# SQLite database

`app.db` is the local SQLite database; `schema.sql` defines its structure.
Database files are excluded from Git. Keep the schema in version control.

The `users` table contains:

| Column | Purpose |
| --- | --- |
| id | Integer primary key, automatically assigned when omitted on insert |
| login | Required unique login |
| email | Required unique email |
| password_hash | Encoded password hash; never a plaintext password |
| version | Integer revision for optimistic locking; starts at zero |
| created_at | Creation time in UTC, set automatically |
| updated_at | Last modification time in UTC, maintained by a trigger |

Dates use SQLite CURRENT_TIMESTAMP: `YYYY-MM-DD HH:MM:SS`.
Login and email uniqueness use SQLite NOCASE (ASCII case-insensitive; this is
not full Unicode case folding). Password confirmation is a form-only value
and is not persisted.

Existing databases are upgraded explicitly with `scripts/migrate-database.ps1`.
Migration 001 adds `version` and updates the timestamp trigger. It creates a
consistent `app.db.backup-*` snapshot with SQLite VACUUM INTO before changing the
schema. Stop the application during migration. The migration is transactional
and a repeat run detects the existing version column. Startup does not migrate
the database automatically. Backups contain the same sensitive data as the DB.

Model updates compare and advance `version`. The trigger also advances it for
direct changes to login/email/password_hash, so raw CRUD cannot silently bypass
model conflict detection. Other tables used with Model need their own version
column and corresponding policy for writes outside Model. Changes made through
unrestricted external SQL must follow that policy too.

A new database is created with `scripts/init-database.ps1`. The schema is applied
to a temporary file in one transaction, which is then renamed without replacing an
existing file: an interrupted run leaves no half-created database, and an existing
database is never overwritten.
The packaged exe does the same on its first launch, in `%LOCALAPPDATA%\TestApp\database\app.db`
(see Packaging in the README); started from sources, the app never creates the database.

Java JDBC access is implemented in `core/database` and the User model lives in
`app/models`. Registration and login are described in [registration.md](registration.md)
and [authentication.md](authentication.md); see [database-core.md](database-core.md)
for usage and isolated tests.
