-- Passkeys (WebAuthn credentials) a user registered (PasskeyService). attested_credential_data is the whole
-- AttestedCredentialData (AAGUID, credential id, COSE public key) as webauthn4j's AttestedCredentialDataConverter
-- writes it, so a CredentialRecord is rebuilt from webauthn4j's own encoding rather than from hand-split columns.
-- uv_initialized / backup_eligible / backed_up are the record's flags: backup eligibility must not change between
-- assertions, and sign_count catches a cloned authenticator.
CREATE TABLE IF NOT EXISTS vomatt.passkey_credentials (
    id                       UUID         PRIMARY KEY,
    user_id                  UUID         NOT NULL REFERENCES vomatt.users (id) ON DELETE CASCADE,
    credential_id            BYTEA        NOT NULL UNIQUE,
    attested_credential_data BYTEA        NOT NULL,
    sign_count               BIGINT       NOT NULL DEFAULT 0,
    transports               TEXT[]       NOT NULL DEFAULT '{}',
    uv_initialized           BOOLEAN      NOT NULL,
    backup_eligible          BOOLEAN      NOT NULL,
    backed_up                BOOLEAN      NOT NULL,
    name                     VARCHAR(100),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at             TIMESTAMPTZ
);

-- A user's passkeys (registration excludeCredentials, the passkey list) and ON DELETE CASCADE from users
CREATE INDEX IF NOT EXISTS idx_passkey_credentials_user ON vomatt.passkey_credentials (user_id);
