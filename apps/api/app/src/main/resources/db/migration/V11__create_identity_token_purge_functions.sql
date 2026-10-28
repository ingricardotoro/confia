-- Eleventh migration (background-jobs-with-db-scheduler, slice 5a-1; design.md decision 16;
-- specs/build-integrity/spec.md). Creates the two SECURITY DEFINER purge functions for the identity
-- tokens whose retention ends: expired password-reset tokens and revoked or expired refresh-token
-- families. No role receives DELETE on any table: the only way to remove these rows is to call a
-- function below, and only confia_admin_app may call them. The administrative HTTP routes run as the
-- same database role, so the functions also refuse any actor other than 'system', which only the
-- worker process sets.
--
-- Both functions follow one template:
--   * SECURITY DEFINER, owned by confia_owner (the role Flyway migrates as; no explicit OWNER TO is
--     needed, the same precedent as V3) with search_path pinned to pg_catalog, public. Every object
--     is also qualified with public., because pg_temp is searched first for relations unless it is
--     named, and a caller could otherwise shadow a table with a temporary one.
--   * A hard-coded floor: the effective cut-off is LEAST(p_cutoff, now() - floor). The caller passes
--     its own cut-off, computed from its Clock, so the boundary is testable to the second; the floor
--     means a caller can only move the cut-off backwards, never delete a row younger than the floor,
--     even by passing a cut-off in the future. An exception when the cut-off exceeds the floor was
--     rejected: a difference of milliseconds between the Java and the PostgreSQL clocks would then
--     fail the production job.
--   * The institution context is mandatory. confia_owner is NOBYPASSRLS and row-level security is
--     forced, so a DELETE without app.institution_id would see zero rows and succeed silently. The
--     function refuses instead: a purge without context never reads as a success of zero rows.
--   * Only the 'system' actor may purge.
--   * A bounded batch: p_limit is clamped to 1..10000 and the caller repeats while the batch is full.

-- Password-reset tokens: purgeable once expired for more than the floor (24 hours). No foreign key
-- references this table.
CREATE FUNCTION identity_purge_password_reset_tokens(p_cutoff timestamptz, p_limit integer)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
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
    'LEAST(p_cutoff, now() - 24 hours). SECURITY DEFINER: no role holds DELETE on the table. Requires '
    'app.institution_id and the system actor. Returns the number of rows deleted.';

REVOKE ALL ON FUNCTION identity_purge_password_reset_tokens(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_purge_password_reset_tokens(timestamptz, integer)
    TO confia_admin_app;

-- Refresh-token families: purgeable once revoked, or past their absolute expiry, for more than the
-- floor (30 days). The absolute expiry is used, never inactivity: inactivity lives in code
-- (SessionLifetimePolicy) and the absolute expiry is always later, so it is the conservative bound.
--
-- A batch is a set of families, never of tokens: the function locks up to p_limit purgeable
-- families in id order, deletes ALL of their tokens, then the families, so a family with more
-- tokens than the batch goes whole. Locking in id order matches rotation, which locks one family and
-- then its tokens, so a purge and a rotation cannot wait for each other in a cycle. The tokens go
-- first because the family key is immediate; the successor key between tokens of one family is
-- deferred and holds at commit, when no token of the family remains. Everything is one statement
-- sequence of one function call: a failure leaves no family half deleted.
CREATE FUNCTION identity_purge_refresh_token_families(
        p_cutoff timestamptz, p_limit integer,
        OUT purged_families bigint, OUT purged_tokens bigint)
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    v_institution uuid := NULLIF(current_setting('app.institution_id', true), '')::uuid;
    v_cutoff      timestamptz := LEAST(p_cutoff, now() - interval '30 days');
    v_limit       integer := LEAST(GREATEST(p_limit, 1), 10000);
    v_families    uuid[];
BEGIN
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
    'families in id order. SECURITY DEFINER: no role holds DELETE on either table. Requires '
    'app.institution_id and the system actor. Returns the families and the tokens deleted.';

REVOKE ALL ON FUNCTION identity_purge_refresh_token_families(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_purge_refresh_token_families(timestamptz, integer)
    TO confia_admin_app;
