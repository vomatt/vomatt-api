-- One-off: moves flyway_schema_history from sequential versions (V1..V14) to the timestamp versions the migration files
-- were renamed to. Run it on every existing database BEFORE deploying the renamed files; otherwise Flyway finds V1..V14
-- applied but missing and the new versions pending, and startup fails validation.
-- Checksums and descriptions stay valid: only the file names changed, not their contents.
-- Re-runnable: rows already moved no longer match the old versions. A database baselined at 1 (no V1 row) has its
-- BASELINE row moved too, matching spring.flyway.baseline-version.
BEGIN;

WITH renamed (old_version, new_version, new_script) AS (VALUES
    ('1',  '20261027125251', 'V20261027125251__baseline.sql'),
    ('2',  '20261027125252', 'V20261027125252__catch_up.sql'),
    ('3',  '20261027154252', 'V20261027154252__vote_shares.sql'),
    ('4',  '20261027164231', 'V20261027164231__vote_view_hours.sql'),
    ('5',  '20261028090749', 'V20261028090749__passkey_credentials.sql'),
    ('6',  '20261029095256', 'V20261029095256__vote_reports.sql'),
    ('7',  '20261029095257', 'V20261029095257__vote_bookmarks.sql'),
    ('8',  '20261029095258', 'V20261029095258__comment_mentions.sql'),
    ('9',  '20261029095259', 'V20261029095259__tag_follows.sql'),
    ('10', '20261029095947', 'V20261029095947__mark_existing_likes_read.sql'),
    ('11', '20261106092526', 'V20261106092526__clear_derived_display_names.sql'),
    ('12', '20261107114942', 'V20261107114942__contact_messages.sql'),
    ('13', '20261107141720', 'V20261107141720__passkey_admin_scope.sql'),
    ('14', '20261107141721', 'V20261107141721__invitee_keys_by_kind.sql')
)
UPDATE vomatt.flyway_schema_history h
SET version = r.new_version,
    -- the BASELINE row's script is '<< Flyway Baseline >>', not a file
    script  = CASE WHEN h.type = 'SQL' THEN r.new_script ELSE h.script END
FROM renamed r
WHERE h.version = r.old_version;

-- Expect every versioned row to be a timestamp now
SELECT installed_rank, version, description, type, script, success
FROM vomatt.flyway_schema_history
ORDER BY installed_rank;

COMMIT;
