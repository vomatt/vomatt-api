-- The resend window is checked per scope and address, whoever sent it; the primary key leads with sender_id
CREATE INDEX IF NOT EXISTS invitation_emails_scope_email_idx ON vomatt.invitation_emails (scope_id, email, sent_at);
