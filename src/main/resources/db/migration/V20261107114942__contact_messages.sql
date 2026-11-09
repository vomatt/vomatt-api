-- A question sent from the about site's contact form (POST /api/contact). Senders are anonymous, so there is no user
-- reference; staff read the messages in the back office (GET /api/admin/contact-messages), newest first.
CREATE TABLE IF NOT EXISTS vomatt.contact_messages (
    id         UUID         PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    email      VARCHAR(254) NOT NULL,
    message    TEXT         NOT NULL,
    locale     VARCHAR(8)   NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Back-office list order
CREATE INDEX IF NOT EXISTS idx_contact_messages_created ON vomatt.contact_messages (created_at DESC, id DESC);
