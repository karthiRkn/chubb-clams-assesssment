-- Who submitted the claim: the username in the caller's token. A claimant may only read claims they submitted; staff
-- (officers, managers, administrators) read all of them.
-- NULL for claims created before this column existed: only staff can see those.
-- No index: it is only compared on a claim that was already found through its unique claim_id.
ALTER TABLE claims ADD COLUMN created_by VARCHAR(100);
