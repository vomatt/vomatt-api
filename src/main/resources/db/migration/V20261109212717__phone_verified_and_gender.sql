-- phone_verified_at: when the account proved it owns phone_number by SMS OTP (sign-up or bind); null = not verified.
-- gender: MALE / FEMALE / OTHER / UNDISCLOSED; null = not provided.
ALTER TABLE vomatt.users ADD COLUMN IF NOT EXISTS phone_verified_at TIMESTAMPTZ NULL;
ALTER TABLE vomatt.users ADD COLUMN IF NOT EXISTS gender VARCHAR(20) NULL;

-- Until now phone_number was written only by phone OTP sign-up, so every stored number was verified at sign-up. Idempotent.
UPDATE vomatt.users
SET phone_verified_at = created_at
WHERE phone_number IS NOT NULL
  AND phone_verified_at IS NULL;
