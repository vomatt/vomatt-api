-- Whether a passkey was registered by an admin console (admin-scope) session. Only such a passkey signs in to the
-- admin console, so a consumer session of an admin account cannot enrol one that does. Existing passkeys start as
-- consumer-only: an admin signs in with an email code and registers a passkey again from the admin console.
ALTER TABLE vomatt.passkey_credentials ADD COLUMN IF NOT EXISTS admin_scope BOOLEAN NOT NULL DEFAULT false;
