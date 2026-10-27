-- =============================================================================
-- Migration: rename Groups to Invite Lists (groups -> invite_lists, group_members -> invite_list_members)
--
-- Run: psql "$DB" -v ON_ERROR_STOP=1 -f 2026-10-22-invite-lists.sql
-- Run it BEFORE starting the API: Flyway (db/migration, formerly schema.sql) would otherwise create empty invite_lists tables and this rename would fail.
-- Apply 2026-10-14-canonical-gmail.sql first if this database has not had it: it targets group_members.
-- Re-runnable: does nothing once vomatt.groups is gone; aborts with a hint if Flyway (formerly schema.sql) already created invite_lists.
--
-- Rule: data is kept as is; tables, the group_id column and every constraint and index get the names a fresh
--   schema.sql produces, including the ones Postgres generated (pkey, fkey, unique key).
-- =============================================================================

DO $$
BEGIN
    IF to_regclass('vomatt.groups') IS NULL THEN
        RETURN;
    END IF;
    IF to_regclass('vomatt.invite_lists') IS NOT NULL THEN
        RAISE EXCEPTION 'vomatt.groups and vomatt.invite_lists both exist: schema.sql ran first. '
            'Drop the empty invite_list_members and invite_lists tables, then rerun this script.';
    END IF;
    ALTER TABLE vomatt.groups RENAME TO invite_lists;
    ALTER TABLE vomatt.invite_lists RENAME CONSTRAINT groups_pkey TO invite_lists_pkey;
    ALTER TABLE vomatt.invite_lists RENAME CONSTRAINT groups_owner_id_fkey TO invite_lists_owner_id_fkey;
    ALTER INDEX vomatt.idx_groups_owner_id RENAME TO idx_invite_lists_owner_id;

    ALTER TABLE vomatt.group_members RENAME TO invite_list_members;
    ALTER TABLE vomatt.invite_list_members RENAME COLUMN group_id TO invite_list_id;
    ALTER TABLE vomatt.invite_list_members RENAME CONSTRAINT group_members_pkey TO invite_list_members_pkey;
    ALTER TABLE vomatt.invite_list_members
        RENAME CONSTRAINT group_members_group_id_user_id_key TO invite_list_members_invite_list_id_user_id_key;
    ALTER TABLE vomatt.invite_list_members RENAME CONSTRAINT group_members_group_id_fkey TO invite_list_members_invite_list_id_fkey;
    ALTER TABLE vomatt.invite_list_members RENAME CONSTRAINT group_members_user_id_fkey TO invite_list_members_user_id_fkey;
    ALTER TABLE vomatt.invite_list_members
        RENAME CONSTRAINT chk_group_members_user_or_email TO chk_invite_list_members_user_or_email;
    ALTER INDEX vomatt.idx_group_members_user_id RENAME TO idx_invite_list_members_user_id;
    ALTER INDEX vomatt.uk_group_members_group_email RENAME TO uk_invite_list_members_list_email;
    ALTER INDEX vomatt.idx_group_members_email RENAME TO idx_invite_list_members_email;
END $$;
