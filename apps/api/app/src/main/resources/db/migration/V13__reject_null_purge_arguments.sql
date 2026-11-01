-- Thirteenth migration (background-jobs-with-db-scheduler, slice 6d, task 3.9; design.md decision
-- 16, "Argumentos nulos", owner decision of 2026-11-01; specs/build-integrity/spec.md, scenarios
-- BI117 and BI118). Redefines the five SECURITY DEFINER purge functions of V11 and V12 so that a
-- NULL p_cutoff or a NULL p_limit fails instead of being absorbed. Until now LEAST ignored a NULL
-- cut-off, which became the floor, and GREATEST raised a NULL limit to 1. That never deleted more
-- than the floor allowed, but it hid a defect: no caller passes NULL, so a NULL is a bug of the
-- caller and the purge must say so.
--
-- V11 and V12 are applied and are never edited, so this migration replaces each function with
-- CREATE OR REPLACE FUNCTION: the same signature, the same body, the same SET search_path and SET
-- TimeZone clauses, and the same LANGUAGE and SECURITY DEFINER. The only change is the first two
-- checks of each body, in this order:
--   * IF p_cutoff IS NULL, then IF p_limit IS NULL: both raise confia_purge_argument_missing with
--     SQLSTATE P0001, before anything is read or deleted.
--   * They come before the institution context check and the 'system' actor check of the template
--     (ADR-0025): a NULL argument is a defect that does not depend on the session, so the error
--     names the cause even when the session is also wrong.
-- A non-NULL limit is still clamped to 1..10000 and a non-NULL cut-off is still capped by the floor
-- with LEAST. The V12 functions keep the purge condition written twice, in the batch subquery and
-- on the deleted row.
--
-- CREATE OR REPLACE keeps the owner (confia_owner, the role Flyway migrates as) and the privileges
-- of an existing function. The REVOKE ALL ... FROM PUBLIC and GRANT EXECUTE ... TO confia_admin_app
-- of each function are repeated anyway, so this file states the whole privilege rule of each
-- function on its own; both are idempotent. No role receives DELETE on any table. The COMMENT ON
-- FUNCTION of each is restated with the new rule. The generated jOOQ routines do not change: the
-- signatures are the same. V13SchemaDiffIT proves that this file changes the five bodies and
-- nothing else, and keeps the owner, the settings and the EXECUTE grants of each.

-- Password-reset tokens (V11): floor of 24 hours; no foreign key references this table.
CREATE OR REPLACE FUNCTION identity_purge_password_reset_tokens(
        p_cutoff timestamptz, p_limit integer)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
    SET TimeZone = 'UTC'
AS $$
DECLARE
    v_institution uuid := NULLIF(current_setting('app.institution_id', true), '')::uuid;
    v_cutoff      timestamptz := LEAST(p_cutoff, now() - interval '24 hours');
    v_limit       integer := LEAST(GREATEST(p_limit, 1), 10000);
    v_count       bigint;
BEGIN
    IF p_cutoff IS NULL THEN
        RAISE EXCEPTION 'confia_purge_argument_missing' USING ERRCODE = 'P0001';
    END IF;
    IF p_limit IS NULL THEN
        RAISE EXCEPTION 'confia_purge_argument_missing' USING ERRCODE = 'P0001';
    END IF;
    IF v_institution IS NULL THEN
        RAISE EXCEPTION 'confia_purge_context_missing' USING ERRCODE = 'P0001';
    END IF;
    IF current_setting('app.actor_kind', true) IS DISTINCT FROM 'system' THEN
        RAISE EXCEPTION 'confia_purge_actor_not_system' USING ERRCODE = 'P0001';
    END IF;

    DELETE FROM public.identity_password_reset_token t
     WHERE (t.institution_id, t.id) IN (
               SELECT s.institution_id, s.id
                 FROM public.identity_password_reset_token s
                WHERE s.institution_id = v_institution AND s.expires_at < v_cutoff
                LIMIT v_limit);
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END
$$;

COMMENT ON FUNCTION identity_purge_password_reset_tokens(timestamptz, integer) IS
    'Deletes up to p_limit password-reset tokens of the current institution that expired before '
    'LEAST(p_cutoff, now() - 24 hours). SECURITY DEFINER: no role holds DELETE on the table. '
    'Rejects a NULL p_cutoff or p_limit with confia_purge_argument_missing before any other check. '
    'Requires app.institution_id and the system actor. Returns the number of rows deleted.';

REVOKE ALL ON FUNCTION identity_purge_password_reset_tokens(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_purge_password_reset_tokens(timestamptz, integer)
    TO confia_admin_app;

-- Refresh-token families (V11): floor of 30 days; a batch is a set of families, locked in id order,
-- deleted whole with every token. The two OUT parameters are part of the signature and stay.
CREATE OR REPLACE FUNCTION identity_purge_refresh_token_families(
        p_cutoff timestamptz, p_limit integer,
        OUT purged_families bigint, OUT purged_tokens bigint)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
    SET TimeZone = 'UTC'
AS $$
DECLARE
    v_institution uuid := NULLIF(current_setting('app.institution_id', true), '')::uuid;
    v_cutoff      timestamptz := LEAST(p_cutoff, now() - interval '30 days');
    v_limit       integer := LEAST(GREATEST(p_limit, 1), 10000);
    v_families    uuid[];
BEGIN
    IF p_cutoff IS NULL THEN
        RAISE EXCEPTION 'confia_purge_argument_missing' USING ERRCODE = 'P0001';
    END IF;
    IF p_limit IS NULL THEN
        RAISE EXCEPTION 'confia_purge_argument_missing' USING ERRCODE = 'P0001';
    END IF;
    IF v_institution IS NULL THEN
        RAISE EXCEPTION 'confia_purge_context_missing' USING ERRCODE = 'P0001';
    END IF;
    IF current_setting('app.actor_kind', true) IS DISTINCT FROM 'system' THEN
        RAISE EXCEPTION 'confia_purge_actor_not_system' USING ERRCODE = 'P0001';
    END IF;

    SELECT COALESCE(array_agg(locked.id ORDER BY locked.id), ARRAY[]::uuid[])
      INTO v_families
      FROM (SELECT f.id
              FROM public.identity_refresh_token_family f
             WHERE f.institution_id = v_institution
               AND (f.revoked_at < v_cutoff OR f.absolute_expires_at < v_cutoff)
             ORDER BY f.id
             LIMIT v_limit
               FOR UPDATE) locked;

    DELETE FROM public.identity_refresh_token t
     WHERE t.institution_id = v_institution AND t.family_id = ANY (v_families);
    GET DIAGNOSTICS purged_tokens = ROW_COUNT;

    DELETE FROM public.identity_refresh_token_family f
     WHERE f.institution_id = v_institution AND f.id = ANY (v_families);
    GET DIAGNOSTICS purged_families = ROW_COUNT;
END
$$;

COMMENT ON FUNCTION identity_purge_refresh_token_families(timestamptz, integer) IS
    'Deletes up to p_limit refresh-token families of the current institution revoked or past their '
    'absolute expiry before LEAST(p_cutoff, now() - 30 days), with every token of each. Locks the '
    'families in id order. SECURITY DEFINER: no role holds DELETE on either table. Rejects a NULL '
    'p_cutoff or p_limit with confia_purge_argument_missing before any other check. Requires '
    'app.institution_id and the system actor. Returns the families and the tokens deleted.';

REVOKE ALL ON FUNCTION identity_purge_refresh_token_families(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_purge_refresh_token_families(timestamptz, integer)
    TO confia_admin_app;

-- Idempotency keys (V12): floor of 24 hours.
CREATE OR REPLACE FUNCTION shared_purge_idempotency_keys(p_cutoff timestamptz, p_limit integer)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
    SET TimeZone = 'UTC'
AS $$
DECLARE
    v_institution uuid := NULLIF(current_setting('app.institution_id', true), '')::uuid;
    v_cutoff      timestamptz := LEAST(p_cutoff, now() - interval '24 hours');
    v_limit       integer := LEAST(GREATEST(p_limit, 1), 10000);
    v_count       bigint;
BEGIN
    IF p_cutoff IS NULL THEN
        RAISE EXCEPTION 'confia_purge_argument_missing' USING ERRCODE = 'P0001';
    END IF;
    IF p_limit IS NULL THEN
        RAISE EXCEPTION 'confia_purge_argument_missing' USING ERRCODE = 'P0001';
    END IF;
    IF v_institution IS NULL THEN
        RAISE EXCEPTION 'confia_purge_context_missing' USING ERRCODE = 'P0001';
    END IF;
    IF current_setting('app.actor_kind', true) IS DISTINCT FROM 'system' THEN
        RAISE EXCEPTION 'confia_purge_actor_not_system' USING ERRCODE = 'P0001';
    END IF;

    DELETE FROM public.shared_idempotency_key k
     WHERE k.institution_id = v_institution
       AND k.expires_at < v_cutoff
       AND (k.institution_id, k.endpoint, k.idempotency_key) IN (
               SELECT s.institution_id, s.endpoint, s.idempotency_key
                 FROM public.shared_idempotency_key s
                WHERE s.institution_id = v_institution AND s.expires_at < v_cutoff
                LIMIT v_limit);
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END
$$;

COMMENT ON FUNCTION shared_purge_idempotency_keys(timestamptz, integer) IS
    'Deletes up to p_limit idempotency keys of the current institution that expired before '
    'LEAST(p_cutoff, now() - 24 hours). SECURITY DEFINER: no role holds DELETE on the table. '
    'Rejects a NULL p_cutoff or p_limit with confia_purge_argument_missing before any other check. '
    'Requires app.institution_id and the system actor. Returns the number of rows deleted.';

REVOKE ALL ON FUNCTION shared_purge_idempotency_keys(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION shared_purge_idempotency_keys(timestamptz, integer) TO confia_admin_app;

-- Login backoff (V12): floor of 24 hours on the last attempt.
CREATE OR REPLACE FUNCTION identity_purge_login_backoff(p_cutoff timestamptz, p_limit integer)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
    SET TimeZone = 'UTC'
AS $$
DECLARE
    v_institution uuid := NULLIF(current_setting('app.institution_id', true), '')::uuid;
    v_cutoff      timestamptz := LEAST(p_cutoff, now() - interval '24 hours');
    v_limit       integer := LEAST(GREATEST(p_limit, 1), 10000);
    v_count       bigint;
BEGIN
    IF p_cutoff IS NULL THEN
        RAISE EXCEPTION 'confia_purge_argument_missing' USING ERRCODE = 'P0001';
    END IF;
    IF p_limit IS NULL THEN
        RAISE EXCEPTION 'confia_purge_argument_missing' USING ERRCODE = 'P0001';
    END IF;
    IF v_institution IS NULL THEN
        RAISE EXCEPTION 'confia_purge_context_missing' USING ERRCODE = 'P0001';
    END IF;
    IF current_setting('app.actor_kind', true) IS DISTINCT FROM 'system' THEN
        RAISE EXCEPTION 'confia_purge_actor_not_system' USING ERRCODE = 'P0001';
    END IF;

    DELETE FROM public.identity_login_backoff b
     WHERE b.institution_id = v_institution
       AND b.last_attempt_at < v_cutoff
       AND (b.institution_id, b.identifier_hash) IN (
               SELECT s.institution_id, s.identifier_hash
                 FROM public.identity_login_backoff s
                WHERE s.institution_id = v_institution AND s.last_attempt_at < v_cutoff
                LIMIT v_limit);
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END
$$;

COMMENT ON FUNCTION identity_purge_login_backoff(timestamptz, integer) IS
    'Deletes up to p_limit login backoff rows of the current institution last attempted before '
    'LEAST(p_cutoff, now() - 24 hours). SECURITY DEFINER: no role holds DELETE on the table. '
    'Rejects a NULL p_cutoff or p_limit with confia_purge_argument_missing before any other check. '
    'Requires app.institution_id and the system actor. Returns the number of rows deleted.';

REVOKE ALL ON FUNCTION identity_purge_login_backoff(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_purge_login_backoff(timestamptz, integer) TO confia_admin_app;

-- TOTP backoff (V12): floor of 24 hours on the last attempt.
CREATE OR REPLACE FUNCTION identity_purge_mfa_totp_backoff(p_cutoff timestamptz, p_limit integer)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
    SET TimeZone = 'UTC'
AS $$
DECLARE
    v_institution uuid := NULLIF(current_setting('app.institution_id', true), '')::uuid;
    v_cutoff      timestamptz := LEAST(p_cutoff, now() - interval '24 hours');
    v_limit       integer := LEAST(GREATEST(p_limit, 1), 10000);
    v_count       bigint;
BEGIN
    IF p_cutoff IS NULL THEN
        RAISE EXCEPTION 'confia_purge_argument_missing' USING ERRCODE = 'P0001';
    END IF;
    IF p_limit IS NULL THEN
        RAISE EXCEPTION 'confia_purge_argument_missing' USING ERRCODE = 'P0001';
    END IF;
    IF v_institution IS NULL THEN
        RAISE EXCEPTION 'confia_purge_context_missing' USING ERRCODE = 'P0001';
    END IF;
    IF current_setting('app.actor_kind', true) IS DISTINCT FROM 'system' THEN
        RAISE EXCEPTION 'confia_purge_actor_not_system' USING ERRCODE = 'P0001';
    END IF;

    DELETE FROM public.identity_mfa_totp_backoff b
     WHERE b.institution_id = v_institution
       AND b.last_attempt_at < v_cutoff
       AND (b.institution_id, b.account_id) IN (
               SELECT s.institution_id, s.account_id
                 FROM public.identity_mfa_totp_backoff s
                WHERE s.institution_id = v_institution AND s.last_attempt_at < v_cutoff
                LIMIT v_limit);
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END
$$;

COMMENT ON FUNCTION identity_purge_mfa_totp_backoff(timestamptz, integer) IS
    'Deletes up to p_limit TOTP backoff rows of the current institution last attempted before '
    'LEAST(p_cutoff, now() - 24 hours). SECURITY DEFINER: no role holds DELETE on the table. '
    'Rejects a NULL p_cutoff or p_limit with confia_purge_argument_missing before any other check. '
    'Requires app.institution_id and the system actor. Returns the number of rows deleted.';

REVOKE ALL ON FUNCTION identity_purge_mfa_totp_backoff(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_purge_mfa_totp_backoff(timestamptz, integer) TO confia_admin_app;
