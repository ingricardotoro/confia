-- Twelfth migration (background-jobs-with-db-scheduler, slice 5b-1; design.md decision 16;
-- specs/build-integrity/spec.md and specs/identity/spec.md). Creates the three SECURITY DEFINER purge
-- functions of the rows whose retention ends after 24 hours (decision P3): expired idempotency keys,
-- idle login backoff rows and idle TOTP backoff rows, and the two indexes the first two scan by. No
-- role receives DELETE on any table: the only way to remove these rows is to call a function below,
-- and only confia_admin_app may call them. The administrative HTTP routes run as the same database
-- role, so the functions also refuse any actor other than 'system', which only the worker process
-- sets.
--
-- The three functions follow the template of V11, recorded in ADR-0025:
--   * SECURITY DEFINER, owned by confia_owner (the role Flyway migrates as), with search_path pinned
--     to pg_catalog, public and every relation qualified with public., so a temporary table of the
--     caller cannot shadow a table. TimeZone is pinned to UTC, so the floor below never depends on
--     the session time zone.
--   * A hard-coded floor of 24 hours: the effective cut-off is LEAST(p_cutoff, now() - floor). The
--     caller passes its own cut-off, computed from its Clock; it can only move the cut-off
--     backwards, never delete a row younger than the floor, even with a cut-off in the future. As in
--     V11, a NULL p_cutoff becomes the floor (LEAST ignores NULL) and a NULL p_limit becomes 1.
--   * The institution context is mandatory, and only the 'system' actor may purge: both raise
--     P0001, so a purge without context never reads as a success of zero rows.
--   * A bounded batch: p_limit is clamped to 1..10000 and the caller repeats while the batch is full.
--   * The purge condition is written twice: in the subquery that picks the batch and again on the
--     row being deleted. These rows, unlike the V11 ones, come back to life: a login refreshes
--     last_attempt_at and a request reuses an expired key by moving expires_at. When the DELETE
--     waits for such a writer, PostgreSQL re-checks only the conditions on the deleted row against
--     its new version; the subquery's earlier answer is reused as it was. Without the repeated
--     condition the purge would delete a row the writer had just made recent again.

-- Idempotency keys: purgeable once expired for more than the floor. An expired key kept by the floor
-- is still reused by updating its row; a purged key is simply a new key again.
CREATE FUNCTION shared_purge_idempotency_keys(p_cutoff timestamptz, p_limit integer)
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
    'LEAST(p_cutoff, now() - 24 hours). SECURITY DEFINER: no role holds DELETE on the table. Requires '
    'app.institution_id and the system actor. Returns the number of rows deleted.';

REVOKE ALL ON FUNCTION shared_purge_idempotency_keys(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION shared_purge_idempotency_keys(timestamptz, integer) TO confia_admin_app;

-- Login backoff: purgeable once its last attempt is older than the floor. The table grows without
-- bound under a distributed attack against random identifiers, hence its index below. A login that
-- arrives while the purge holds the row waits for the DELETE, finds no row and recreates it through
-- the insert branch of its claim, the existing first-failure path.
CREATE FUNCTION identity_purge_login_backoff(p_cutoff timestamptz, p_limit integer)
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
    'LEAST(p_cutoff, now() - 24 hours). SECURITY DEFINER: no role holds DELETE on the table. Requires '
    'app.institution_id and the system actor. Returns the number of rows deleted.';

REVOKE ALL ON FUNCTION identity_purge_login_backoff(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_purge_login_backoff(timestamptz, integer) TO confia_admin_app;

-- TOTP backoff: the same rule. One row per account, so the primary key, which leads with
-- institution_id, bounds the scan and no new index is created.
CREATE FUNCTION identity_purge_mfa_totp_backoff(p_cutoff timestamptz, p_limit integer)
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
    'LEAST(p_cutoff, now() - 24 hours). SECURITY DEFINER: no role holds DELETE on the table. Requires '
    'app.institution_id and the system actor. Returns the number of rows deleted.';

REVOKE ALL ON FUNCTION identity_purge_mfa_totp_backoff(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_purge_mfa_totp_backoff(timestamptz, integer) TO confia_admin_app;

-- Index for shared_purge_idempotency_keys: the batch subquery filters by institution_id and a range
-- of expires_at. Without it the daily purge reads every key of the institution. Write cost: one
-- index entry per key insert and per reuse that moves expires_at. V4 deferred this index to the
-- change that owns the purge, which is this one.
CREATE INDEX shared_idempotency_key_expires_idx
    ON shared_idempotency_key (institution_id, expires_at);

-- Index for identity_purge_login_backoff: the same range on last_attempt_at, over a table that grows
-- without bound under a distributed attack (V5, "Purge is a named, undelivered gap"). Write cost:
-- one index entry per new identifier and per saved attempt, which moves last_attempt_at.
CREATE INDEX identity_login_backoff_last_attempt_idx
    ON identity_login_backoff (institution_id, last_attempt_at);
