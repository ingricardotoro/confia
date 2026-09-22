-- Third business migration (F0 change 5, part B, PR B2b; design.md decisions 4, 5 and 6;
-- specs/audit-trail/spec.md, requirements "Cadena de hash por institución con registro génesis" and
-- "El encadenamiento se calcula en el disparador del motor, no en la aplicación"). Adds the hash
-- chain V2 deliberately left out: shared_audit_log.id, prev_hash and row_hash stayed NOT NULL but
-- unpopulated by any trigger there. This migration is the trigger.

-- Pure, invoker-rights canonicalization of a JSONB value (design.md §6.4), recursive over jsonb's
-- own null/boolean/number/string/array/object typeof. No normalization, no whitespace, numbers as
-- exact `numeric` text (trim_scale, never a double), strings escaped like RFC 8785 (to_json already
-- matches: sonda S1, design.md §10), object keys ordered by their raw UTF-8 byte sequence via
-- convert_to(k, 'UTF8') — NEVER `ORDER BY k`, which depends on the server collation (sonda S5's
-- finding: en_US.utf8 collapses to C-like ordering on this image only, so a test that relied on both
-- orders coinciding would pass here and diverge elsewhere). Mirrored exactly in
-- com.confia.shared.audit.CanonicalAuditRowSerializer#canonicalJson (PR B3a), and the two are cross-
-- checked by a jqwik property against real PostgreSQL output (CanonicalSerializationCrossCheckIT).
CREATE FUNCTION shared_audit_canonical_json(v jsonb) RETURNS text
LANGUAGE plpgsql STABLE AS $$
BEGIN
    RETURN CASE jsonb_typeof(v)
        WHEN 'null'    THEN 'null'
        WHEN 'boolean' THEN CASE WHEN v = 'true'::jsonb THEN 'true' ELSE 'false' END
        WHEN 'number'  THEN trim_scale((v #>> '{}')::numeric)::text
        WHEN 'string'  THEN to_json(v #>> '{}')::text
        WHEN 'array'   THEN '[' || coalesce((SELECT string_agg(shared_audit_canonical_json(e), ','
                                                                 ORDER BY ord)
                                               FROM jsonb_array_elements(v) WITH ORDINALITY AS t(e, ord)),
                                             '') || ']'
        WHEN 'object'  THEN '{' || coalesce((SELECT string_agg(to_json(k)::text || ':'
                                                                 || shared_audit_canonical_json(val),
                                                                 ',' ORDER BY convert_to(k, 'UTF8'))
                                               FROM jsonb_each(v) AS t(k, val)), '') || '}'
    END;
END;
$$;

COMMENT ON FUNCTION shared_audit_canonical_json(jsonb) IS
    'Canonical JSON per design.md §6.4 (openspec/changes/audit-log-and-transaction-runner). Object '
    'keys order by raw UTF-8 bytes (convert_to(k, ''UTF8'')), never the server collation. Mirrored '
    'in com.confia.shared.audit.CanonicalAuditRowSerializer#canonicalJson.';

-- One field of the preimage (design.md §6.2): F(v) = 0x00 if the canonical text is absent (the
-- column was SQL NULL, or, for before_value/after_value, shared_audit_canonical_json already
-- returned NULL for a NULL jsonb input), else 0x01 || the field's UTF-8 byte length as eight
-- network-order bytes (int8send, confirmed available and correct by sonda S6) || the UTF-8 bytes of
-- the canonical text themselves. Factored out once so all 18 fields of shared_audit_row_preimage use
-- literally the same encoding, instead of eighteen hand-written copies that could each drift.
CREATE FUNCTION shared_audit_field_bytes(canon_text text) RETURNS bytea
LANGUAGE sql STABLE AS $$
    SELECT CASE
        WHEN canon_text IS NULL THEN '\x00'::bytea
        ELSE '\x01'::bytea
             || int8send(octet_length(convert_to(canon_text, 'UTF8'))::bigint)
             || convert_to(canon_text, 'UTF8')
    END;
$$;

COMMENT ON FUNCTION shared_audit_field_bytes(text) IS
    'F(v) of design.md §6.2: 0x00 for an absent field, else 0x01 || 8-byte network-order length || '
    'UTF-8 bytes. Internal building block of shared_audit_row_preimage, not itself one of the three '
    'named objects of decision 5.';

-- The full preimage over the 18 signed fields (design.md §6.2: the 15 of docs/03-seguridad.md §12.1
-- plus actor_label, user_agent and trace_id, open question 1 of design.md §15, resolved 2026-09-21).
-- Field order is fixed and written literally here and in CanonicalAuditRow/
-- CanonicalAuditRowSerializer (PR B3a) — a divergence in order is a visible line difference, not a
-- silent comparator bug (design.md §6.2, point 1). occurred_at is encoded as microseconds since the
-- Unix epoch, never ::text, so no session TimeZone/DateStyle setting can change the hash of the same
-- instant (design.md §6.3).
CREATE FUNCTION shared_audit_row_preimage(
    prev_hash      bytea,
    id             bigint,
    institution_id uuid,
    occurred_at    timestamptz,
    actor_id       uuid,
    actor_kind     text,
    actor_label    text,
    source_ip      inet,
    user_agent     text,
    request_id     uuid,
    trace_id       text,
    action         text,
    entity_type    text,
    entity_id      text,
    outcome        text,
    before_value   jsonb,
    after_value    jsonb,
    reason         text,
    approver_id    uuid
) RETURNS bytea
LANGUAGE plpgsql STABLE AS $$
DECLARE
    occurred_at_micros bigint;
    source_ip_canon    text;
BEGIN
    occurred_at_micros :=
        (EXTRACT(EPOCH FROM (occurred_at - TIMESTAMPTZ '1970-01-01 00:00:00+00')) * 1000000)::bigint;
    -- design.md §6.3: address plus mask, always explicit (host(v) || '/' || masklen(v)), never
    -- PostgreSQL's own text form, which omits /32 and /128.
    IF source_ip IS NULL THEN
        source_ip_canon := NULL;
    ELSE
        source_ip_canon := host(source_ip) || '/' || masklen(source_ip);
    END IF;

    RETURN convert_to('confia.audit.v1', 'UTF8')
        || prev_hash
        || shared_audit_field_bytes(id::text)
        || shared_audit_field_bytes(institution_id::text)
        || shared_audit_field_bytes(occurred_at_micros::text)
        || shared_audit_field_bytes(actor_id::text)
        || shared_audit_field_bytes(actor_kind)
        || shared_audit_field_bytes(actor_label)
        || shared_audit_field_bytes(source_ip_canon)
        || shared_audit_field_bytes(user_agent)
        || shared_audit_field_bytes(request_id::text)
        || shared_audit_field_bytes(trace_id)
        || shared_audit_field_bytes(action)
        || shared_audit_field_bytes(entity_type)
        || shared_audit_field_bytes(entity_id)
        || shared_audit_field_bytes(outcome)
        || shared_audit_field_bytes(shared_audit_canonical_json(before_value))
        || shared_audit_field_bytes(shared_audit_canonical_json(after_value))
        || shared_audit_field_bytes(reason)
        || shared_audit_field_bytes(approver_id::text);
END;
$$;

COMMENT ON FUNCTION shared_audit_row_preimage(bytea, bigint, uuid, timestamptz, uuid, text, text,
    inet, text, uuid, text, text, text, text, text, jsonb, jsonb, text, uuid) IS
    'Preimage of design.md §6.2 (openspec/changes/audit-log-and-transaction-runner), 18 signed '
    'fields in fixed order, "confia.audit.v1" format tag. Mirrored in '
    'com.confia.shared.audit.CanonicalAuditRowSerializer#preimage; a change on either side without '
    'the other is a defect.';

-- sha256(bytea) is a built-in PostgreSQL 11+ function (pg_proc.prolang = 12, confirmed by sonda S6):
-- no pgcrypto extension needed, which confia_owner (not SUPERUSER) could not create in any case.
CREATE FUNCTION shared_audit_row_hash(
    prev_hash      bytea,
    id             bigint,
    institution_id uuid,
    occurred_at    timestamptz,
    actor_id       uuid,
    actor_kind     text,
    actor_label    text,
    source_ip      inet,
    user_agent     text,
    request_id     uuid,
    trace_id       text,
    action         text,
    entity_type    text,
    entity_id      text,
    outcome        text,
    before_value   jsonb,
    after_value    jsonb,
    reason         text,
    approver_id    uuid
) RETURNS bytea
LANGUAGE sql STABLE AS $$
    SELECT sha256(shared_audit_row_preimage(prev_hash, id, institution_id, occurred_at, actor_id,
        actor_kind, actor_label, source_ip, user_agent, request_id, trace_id, action, entity_type,
        entity_id, outcome, before_value, after_value, reason, approver_id));
$$;

COMMENT ON FUNCTION shared_audit_row_hash(bytea, bigint, uuid, timestamptz, uuid, text, text, inet,
    text, uuid, text, text, text, text, text, jsonb, jsonb, text, uuid) IS
    'sha256(shared_audit_row_preimage(...)), design.md §6.2. Exposed as a pure, invoker-rights '
    'function precisely so the jqwik cross-check property (PR B3a) can call exactly the code the '
    'trigger below runs, once per generated input, without ever inserting a row.';

-- The chaining trigger itself (design.md decision 5). SECURITY DEFINER, owned by confia_owner (the
-- role Flyway migrates as — this CREATE FUNCTION statement itself runs as confia_owner, so no
-- explicit ALTER FUNCTION ... OWNER TO is needed), with SET search_path pinned to pg_catalog, public:
-- the standard hardening every SECURITY DEFINER function needs, so a manipulated session search_path
-- cannot redirect name resolution. Its only reason to run with the owner's privileges is so it can
-- write shared_audit_chain_head, on which confia_admin_app has no GRANT at all (design.md decision
-- 3: "Ningún rol de aplicación recibe privilegio alguno sobre ella").
--
-- FORCE ROW LEVEL SECURITY on shared_audit_chain_head (V2 migration) still applies to confia_owner
-- even inside this SECURITY DEFINER context (sonda S5, design.md §10, executed against this exact
-- schema shape on 2026-09-22): the trigger only ever reads/writes the row belonging to
-- NEW.institution_id, and an institution mismatch is rejected by the policy from inside the trigger,
-- not silently bypassed. No pg_advisory_xact_lock fallback is needed.
CREATE FUNCTION shared_audit_log_chain() RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    assigned_id bigint;
    prev        bytea;
BEGIN
    -- design.md decision 4: one statement that creates the per-institution head on first use
    -- (returning the génesis id, 1), assigns the next contiguous id on every later insert, and —
    -- the property this whole design relies on — takes the row lock on that institution's head,
    -- serializing any second concurrent writer of the same institution until this transaction
    -- commits or rolls back (measured directly by sonda S4: a second writer blocked 4407 ms).
    INSERT INTO shared_audit_chain_head (institution_id, next_id)
    VALUES (NEW.institution_id, 2)
    ON CONFLICT (institution_id) DO UPDATE
        SET next_id = shared_audit_chain_head.next_id + 1
    RETURNING next_id - 1
    INTO assigned_id;

    -- Safe precisely because the statement above already holds the institution's lock: no other
    -- writer of this institution can be mid-insert here (design.md decision 5, "El paso 2 es
    -- seguro...").
    SELECT row_hash INTO prev
      FROM shared_audit_log
     WHERE institution_id = NEW.institution_id
     ORDER BY id DESC
     LIMIT 1;

    IF prev IS NULL THEN
        prev := decode(repeat('00', 32), 'hex');
    END IF;

    -- The trigger ignores whatever the caller passed in NEW.id, NEW.prev_hash and NEW.row_hash —
    -- always overwritten, never merely defaulted (design.md decision 5, "El disparador ignora lo
    -- que el llamador haya pasado").
    NEW.id        := assigned_id;
    NEW.prev_hash := prev;
    NEW.row_hash  := shared_audit_row_hash(prev, assigned_id, NEW.institution_id, NEW.occurred_at,
        NEW.actor_id, NEW.actor_kind, NEW.actor_label, NEW.source_ip, NEW.user_agent, NEW.request_id,
        NEW.trace_id, NEW.action, NEW.entity_type, NEW.entity_id, NEW.outcome, NEW.before_value,
        NEW.after_value, NEW.reason, NEW.approver_id);

    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION shared_audit_log_chain() IS
    'BEFORE INSERT trigger on shared_audit_log (design.md decision 5). SECURITY DEFINER so it can '
    'write shared_audit_chain_head, which no application role has any GRANT on. Assigns id, '
    'prev_hash and row_hash unconditionally, overwriting anything the caller supplied.';

CREATE TRIGGER shared_audit_log_chain_before_insert
    BEFORE INSERT ON shared_audit_log
    FOR EACH ROW EXECUTE FUNCTION shared_audit_log_chain();
