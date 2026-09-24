-- Fourth business migration (F0 change 6, PR C1; design.md decisions 2 and 3;
-- specs/build-integrity/spec.md). Creates shared_idempotency_key, the marker table the future
-- IdempotentExecutor component (C2b) reads and writes inside a single real transaction so that a
-- failed business effect rolls the marker back with it (ADR-0010).

CREATE TABLE shared_idempotency_key (
    institution_id  UUID        NOT NULL,
    endpoint        TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    response_status INTEGER,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT shared_idempotency_key_pk
        PRIMARY KEY (institution_id, endpoint, idempotency_key),
    CONSTRAINT shared_idempotency_key_status_chk
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    CONSTRAINT shared_idempotency_key_request_hash_chk
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT shared_idempotency_key_endpoint_len_chk
        CHECK (char_length(endpoint) BETWEEN 1 AND 200),
    CONSTRAINT shared_idempotency_key_key_len_chk
        CHECK (char_length(idempotency_key) BETWEEN 1 AND 200),
    CONSTRAINT shared_idempotency_key_completed_chk
        CHECK (status <> 'COMPLETED'
               OR (response_status IS NOT NULL AND response_body IS NOT NULL
                   AND completed_at IS NOT NULL))
);

-- institution_id leads the primary key (design.md decision 2, point 1): there is no surrogate
-- identifier — pgcrypto is not available (change 5's own sonda S6) — and the natural composite key
-- satisfies the unique-index-with-institution-discriminator gate by construction, the same reason
-- shared_audit_log's own indexes lead with institution_id: the row-level-security policy below
-- always filters by it first.
--
-- request_hash is TEXT hex, not BYTEA (ADR-0010): equality in Java is String.equals, with none of
-- the byte-array == trap, and the CHECK regex keeps the format visible in the catalogue. The
-- endpoint/idempotency_key length guards exist because the primary key is a btree index with a
-- size limit on its entries: without them an oversized key would fail with an index-entry-size
-- error instead of a readable constraint violation. This is a technical guard, not a fiscal or API
-- contract; change 7 will additionally validate the header at the edge with Jakarta Bean
-- Validation.
--
-- status copies ADR-0010's domain with FAILED unreachable in this change, said out loud: with the
-- marker and the business effect in the same transaction, a failing effect rolls the whole row
-- back, so there is never a FAILED row to write, and IN_PROGRESS is only observable from inside the
-- very transaction that wrote it. The CHECK is not narrowed, to avoid a fourth discrepancy with the
-- ADR and to avoid forcing a migration the day a non-atomic writer exists. The defensive branch
-- that treats a confirmed IN_PROGRESS row as a conflict (owned by the future executor component) is
-- what keeps a manual intervention or a future non-atomic writer from being read back as a
-- repeatable response.
--
-- There is deliberately no index on expires_at: the purge job that would need it is a later
-- change's own responsibility (see the requirement on absence of physical purge in
-- specs/build-integrity/spec.md), so shipping the index here would be surface with no consumer yet.
-- A caducated row is reused by UPDATE (restartExpired, a later change's executor component), never
-- deleted: no application role receives DELETE on this table.
--
-- created_at uses clock_timestamp(), like shared_audit_log, and not now(): now() is the transaction
-- start instant, and two rows of the same transaction would otherwise share one timestamp.
ALTER TABLE shared_idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE shared_idempotency_key FORCE  ROW LEVEL SECURITY;

-- NULLIF(..., '') and an explicit WITH CHECK, identical to V1-V3: current_setting(..., true)
-- returns NULL for an absent setting and the equality denies; an empty string would otherwise reach
-- ''::uuid and raise a cast error instead of a clean denial.
CREATE POLICY shared_idempotency_key_institution_isolation ON shared_idempotency_key
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

-- Privileges (design.md decision 3; docs/03-seguridad.md section 6.1). REVOKE always precedes any
-- GRANT.
REVOKE ALL ON shared_idempotency_key FROM PUBLIC;

-- UPDATE for confia_admin_app is not an exception, it is the rule applied: docs/03 section 6.1
-- grants UPDATE on non-financial tables, and this table records neither amount nor currency. UPDATE
-- is needed twice — completing the marker and reusing a caducated key — and DELETE is never granted
-- to any application role, which is exactly why caducity is modeled as an UPDATE rather than a
-- delete-then-insert.
GRANT SELECT, INSERT, UPDATE ON shared_idempotency_key TO confia_admin_app;
GRANT SELECT                  ON shared_idempotency_key TO confia_readonly;
-- confia_portal_app: no GRANT at all (docs/03 section 6.1; brecha con destino: F3/F4).
-- confia_owner: schema owner, not subject to GRANT/REVOKE, retains every privilege by definition.
-- confia_backup: reads through pg_read_all_data, already granted on the whole schema, which never
-- bypasses row-level security.
