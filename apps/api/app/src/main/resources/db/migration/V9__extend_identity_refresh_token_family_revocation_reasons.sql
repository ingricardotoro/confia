-- Ninth migration (F0 change 7, part 4b, slice S2; session-endpoints-cookie-and-csrf design.md
-- decision 16; specs/build-integrity/spec.md, scenarios BI39 to BI42). Widens the closed
-- catalogue of revocation reasons with the three producers this change adds: 'logout' (the user
-- ends a session), 'mfa_change' (a second factor is activated or re-enrolled) and
-- 'binding_changed' (a refresh token is presented from another agent and another address).
-- 'admin_revoke' has no producer and stays out; the next migration that adds one widens this
-- CHECK again.
--
-- Nothing else changes: no column, no table, no index, no grant (V8 already grants UPDATE on
-- revoked_at and revoked_reason to confia_admin_app), and no trigger, so a revocation stays final.
-- The rule that revoked_at and revoked_reason are both present or both absent is a different
-- constraint and is not touched. ADD CONSTRAINT validates the rows that exist: the two reasons
-- they can hold are in the new list.

ALTER TABLE identity_refresh_token_family
    DROP CONSTRAINT identity_refresh_token_family_reason_chk;
ALTER TABLE identity_refresh_token_family
    ADD CONSTRAINT identity_refresh_token_family_reason_chk CHECK (revoked_reason IN
        ('reuse_detected', 'password_change', 'logout', 'mfa_change', 'binding_changed'));
