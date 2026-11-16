-- Before 2026-11-16 an invitation to an address with an account left no invitation_emails row, so resendAvailableAt
-- would tell those addresses apart; record every pending invitation by email as sent when it was made.
INSERT INTO vomatt.invitation_emails (sender_id, scope_id, email, sent_at)
SELECT invited_by, community_user_id, email, created_at
FROM vomatt.community_invitations
WHERE email IS NOT NULL AND invited_by IS NOT NULL AND expires_at > now()
ON CONFLICT (sender_id, scope_id, email) DO NOTHING;
