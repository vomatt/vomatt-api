-- Official accounts are renamed community accounts: the 'official' role becomes 'community'; official_members becomes
-- community_members; audit logs on OFFICIAL move to COMMUNITY. Constraint names are left as they are. Safe to rerun.
ALTER TABLE vomatt.users DROP CONSTRAINT IF EXISTS chk_users_identifier;
UPDATE vomatt.users SET roles = array_replace(roles, 'official', 'community') WHERE 'official' = ANY (roles);
ALTER TABLE vomatt.users ADD CONSTRAINT chk_users_identifier
    CHECK (email IS NOT NULL OR phone_number IS NOT NULL OR 'community' = ANY (roles));

UPDATE vomatt.audit_logs SET resource_type = 'COMMUNITY' WHERE resource_type = 'OFFICIAL';

ALTER TABLE IF EXISTS vomatt.official_members RENAME TO community_members;
ALTER INDEX IF EXISTS vomatt.idx_official_members_user RENAME TO idx_community_members_user;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'vomatt' AND table_name = 'community_members' AND column_name = 'official_user_id') THEN
        ALTER TABLE vomatt.community_members RENAME COLUMN official_user_id TO community_user_id;
    END IF;
END $$;
