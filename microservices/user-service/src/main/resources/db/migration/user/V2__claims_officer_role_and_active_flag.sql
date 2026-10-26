-- Two changes to users, in one migration so a database is never half way between them.
--
-- 1. The role OFFICER is renamed CLAIMS_OFFICER. The old CHECK would refuse the new value, so it is dropped first, the
--    stored rows are renamed, and the CHECK is put back with the new list. A user row keeps everything else.
--    (A token issued before this change still carries the old role name and is refused with 401 once the services run
--    the new code; the person simply logs in again.)
-- 2. active: whether the person may log in. Existing users stay active. Deactivating a user stops new logins; a token
--    already issued stays valid until it expires (there is no revocation, see DESIGN_DECISIONS.md).
ALTER TABLE users DROP CONSTRAINT ck_users_role;

UPDATE users SET role = 'CLAIMS_OFFICER' WHERE role = 'OFFICER';

ALTER TABLE users ADD CONSTRAINT ck_users_role CHECK (role IN ('CLAIMANT', 'CLAIMS_OFFICER', 'MANAGER', 'ADMIN'));

ALTER TABLE users ADD COLUMN active BOOLEAN DEFAULT TRUE NOT NULL;
