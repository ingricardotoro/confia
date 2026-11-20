-- Thirty-fourth migration (F4, cut 1; docs/cortes/cashbox-sessions.md). Creates the cash sessions of the cashiers: the opening with
-- a float, the cash movements, the close with the declared count and the approval of a difference. This cut registers no payment and
-- does not touch the ledger.
--
-- THE DATABASE REFUSES, to every role including the owner:
--   * (INV-22) a second OPEN session of the same cashier in the same institution (partial unique index);
--   * (INV-21) a movement on a session that is not open, and a movement in another currency than the one of the session;
--   * (INV-23) a close whose expected amount is not the opening float plus the sum of the movements of the session, whose
--     difference is not zero without a reason of 20 to 500 characters, or that is not made by the cashier of the session;
--     the difference is a GENERATED column: declared - expected;
--   * an approval of a session that is not pending approval, or by the cashier of the session (segregation of duties);
--   * a session opened by anyone but the actor of the transaction (app.actor_id), not open, or stamped with a time that is
--     not now; a movement, a close or an approval written by anyone but the actor of the transaction;
--   * a movement whose accumulated expected amount would end below zero (the trigger locks the session and recomputes it, so
--     two simultaneous withdrawals cannot overdraw it); the resulting expected amount is stored on the movement row;
--   * a DELETE or a TRUNCATE of anything, and an UPDATE of a movement, a close or an approval;
--   * an UPDATE of a session other than the advance of its status along open -> closed | pending_approval and
--     pending_approval -> closed, and only together with the record that justifies it (the close, the approval).
-- Nothing financial is edited: the amounts live in insert-only rows. The status column of the session is the only mutable
-- column, it only moves forward, and the trigger proves the record behind each move.
--
-- Trigger functions are SECURITY INVOKER with a fixed search_path and no PUBLIC execute. The institution comes from the
-- session, never from a request: forced row-level security keeps one institution from reading or writing another.

CREATE FUNCTION cashbox_append_only_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    RAISE EXCEPTION 'cashbox records are append-only: % on % is refused', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

REVOKE ALL ON FUNCTION cashbox_append_only_guard() FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------------------------------
-- Sessions: the status is the only column that ever changes, and only forward
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE cashbox_session (
    institution_id  UUID          NOT NULL,
    id              UUID          NOT NULL,
    cashier_id      UUID          NOT NULL,
    currency        CHAR(3)       NOT NULL,
    opening_float   NUMERIC(14,4) NOT NULL,
    status          TEXT          NOT NULL DEFAULT 'open',
    opened_at       TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    -- The Idempotency-Key of the request that opened the session, PERMANENT, with the hash of what it asked.
    request_key     VARCHAR(128)  NOT NULL,
    request_hash    CHAR(64)      NOT NULL,
    CONSTRAINT cashbox_session_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT cashbox_session_key_uq UNIQUE (institution_id, request_key),
    CONSTRAINT cashbox_session_status_chk CHECK (status IN ('open', 'pending_approval', 'closed')),
    CONSTRAINT cashbox_session_currency_chk CHECK (currency IN ('HNL', 'USD')),
    CONSTRAINT cashbox_session_float_chk CHECK (opening_float >= 0),
    CONSTRAINT cashbox_session_key_chk CHECK (request_key ~ '^[!-~]{1,128}$'),
    CONSTRAINT cashbox_session_hash_chk CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

-- INV-22: one open session per cashier and institution.
CREATE UNIQUE INDEX cashbox_session_one_open_uq ON cashbox_session (institution_id, cashier_id) WHERE status = 'open';
CREATE INDEX cashbox_session_cashier_idx ON cashbox_session (institution_id, cashier_id, opened_at DESC);
CREATE INDEX cashbox_session_status_idx ON cashbox_session (institution_id, status, opened_at DESC);

-- ---------------------------------------------------------------------------------------------------------------------
-- Movements: INSERT-ONLY
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE cashbox_movement (
    institution_id UUID          NOT NULL,
    id             UUID          NOT NULL,
    session_id     UUID          NOT NULL,
    type           TEXT          NOT NULL,
    -- deposit and withdrawal are positive; an adjustment is signed and never zero.
    amount         NUMERIC(14,4) NOT NULL,
    currency       CHAR(3)       NOT NULL,
    notes          TEXT,
    -- The expected cash of the session right after this movement. The trigger computes it under the lock of the session and
    -- overwrites whatever was sent, so an idempotent replay answers the figure of the original call.
    expected_after NUMERIC(14,4) NOT NULL,
    request_key    VARCHAR(128)  NOT NULL,
    request_hash   CHAR(64)      NOT NULL,
    created_by     UUID          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT cashbox_movement_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT cashbox_movement_session_fk FOREIGN KEY (institution_id, session_id)
        REFERENCES cashbox_session (institution_id, id),
    CONSTRAINT cashbox_movement_key_uq UNIQUE (institution_id, request_key),
    CONSTRAINT cashbox_movement_type_chk CHECK (type IN ('deposit', 'withdrawal', 'adjustment')),
    CONSTRAINT cashbox_movement_currency_chk CHECK (currency IN ('HNL', 'USD')),
    CONSTRAINT cashbox_movement_amount_chk CHECK (
        (type IN ('deposit', 'withdrawal') AND amount > 0) OR (type = 'adjustment' AND amount <> 0)),
    CONSTRAINT cashbox_movement_notes_chk CHECK (
        (notes IS NULL AND type <> 'adjustment')
        OR (notes IS NOT NULL AND char_length(btrim(notes)) >= CASE WHEN type = 'adjustment' THEN 20 ELSE 1 END
            AND char_length(notes) <= 500)),
    CONSTRAINT cashbox_movement_after_chk CHECK (expected_after >= 0),
    CONSTRAINT cashbox_movement_key_chk CHECK (request_key ~ '^[!-~]{1,128}$'),
    CONSTRAINT cashbox_movement_hash_chk CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX cashbox_movement_session_idx ON cashbox_movement (institution_id, session_id, created_at, id);

-- ---------------------------------------------------------------------------------------------------------------------
-- Closes: INSERT-ONLY. The difference is generated, never sent.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE cashbox_session_close (
    institution_id    UUID          NOT NULL,
    id                UUID          NOT NULL,
    session_id        UUID          NOT NULL,
    currency          CHAR(3)       NOT NULL,
    declared_amount   NUMERIC(14,4) NOT NULL,
    expected_amount   NUMERIC(14,4) NOT NULL,
    difference_amount NUMERIC(14,4) GENERATED ALWAYS AS (declared_amount - expected_amount) STORED,
    difference_reason TEXT,
    closed_by         UUID          NOT NULL,
    closed_at         TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    request_key       VARCHAR(128)  NOT NULL,
    request_hash      CHAR(64)      NOT NULL,
    CONSTRAINT cashbox_session_close_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT cashbox_session_close_session_fk FOREIGN KEY (institution_id, session_id)
        REFERENCES cashbox_session (institution_id, id),
    -- A session is closed at most once.
    CONSTRAINT cashbox_session_close_session_uq UNIQUE (institution_id, session_id),
    CONSTRAINT cashbox_session_close_key_uq UNIQUE (institution_id, request_key),
    CONSTRAINT cashbox_session_close_currency_chk CHECK (currency IN ('HNL', 'USD')),
    CONSTRAINT cashbox_session_close_declared_chk CHECK (declared_amount >= 0),
    -- INV-23: a difference needs a reason of 20 to 500 characters; no difference needs none.
    CONSTRAINT cashbox_session_close_reason_chk CHECK (
        (difference_amount = 0 AND difference_reason IS NULL)
        OR (difference_amount <> 0 AND difference_reason IS NOT NULL
            AND char_length(btrim(difference_reason)) BETWEEN 20 AND 500 AND char_length(difference_reason) <= 500)),
    CONSTRAINT cashbox_session_close_key_chk CHECK (request_key ~ '^[!-~]{1,128}$'),
    CONSTRAINT cashbox_session_close_hash_chk CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

-- ---------------------------------------------------------------------------------------------------------------------
-- Approvals of a close with a difference: INSERT-ONLY
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE cashbox_session_approval (
    institution_id UUID         NOT NULL,
    id             UUID         NOT NULL,
    session_id     UUID         NOT NULL,
    approved_by    UUID         NOT NULL,
    note           TEXT         NOT NULL,
    approved_at    TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    request_key    VARCHAR(128) NOT NULL,
    request_hash   CHAR(64)     NOT NULL,
    CONSTRAINT cashbox_session_approval_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT cashbox_session_approval_session_fk FOREIGN KEY (institution_id, session_id)
        REFERENCES cashbox_session (institution_id, id),
    -- A close is approved at most once.
    CONSTRAINT cashbox_session_approval_session_uq UNIQUE (institution_id, session_id),
    CONSTRAINT cashbox_session_approval_key_uq UNIQUE (institution_id, request_key),
    CONSTRAINT cashbox_session_approval_note_chk CHECK (char_length(btrim(note)) BETWEEN 1 AND 500
        AND char_length(note) <= 500),
    CONSTRAINT cashbox_session_approval_key_chk CHECK (request_key ~ '^[!-~]{1,128}$'),
    CONSTRAINT cashbox_session_approval_hash_chk CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

-- ---------------------------------------------------------------------------------------------------------------------
-- Trigger functions
-- ---------------------------------------------------------------------------------------------------------------------

-- The cashier opens its own session, now, open. The actor comes from the security context of the transaction, never from the
-- statement. The opening time is the one of the database, within a minute (the application sends its own clock).
CREATE FUNCTION cashbox_session_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    actor uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
BEGIN
    IF actor IS NULL OR NEW.cashier_id <> actor THEN
        RAISE EXCEPTION 'a cash session is opened by its cashier' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status <> 'open' THEN
        RAISE EXCEPTION 'a cash session is born open' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.opened_at NOT BETWEEN clock_timestamp() - interval '1 minute' AND clock_timestamp() + interval '1 minute' THEN
        RAISE EXCEPTION 'a cash session is opened now' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- INV-21: a movement enters only an open session, in its currency, written by its cashier. FOR UPDATE serializes the
-- movements and the close of the session, so the accumulated expected amount computed here is the real one: a movement that
-- races a close waits for it and then sees the session closed, and two withdrawals cannot both pass.
CREATE FUNCTION cashbox_movement_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    owner_session cashbox_session%ROWTYPE;
    actor         uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
    moved         numeric;
BEGIN
    SELECT * INTO owner_session FROM cashbox_session
        WHERE institution_id = NEW.institution_id AND id = NEW.session_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'a movement names a session of the institution' USING ERRCODE = 'check_violation';
    END IF;
    IF owner_session.status <> 'open' THEN
        RAISE EXCEPTION 'a session that is not open accepts no movement' USING ERRCODE = 'check_violation';
    END IF;
    IF owner_session.currency <> NEW.currency THEN
        RAISE EXCEPTION 'a movement is in the currency of its session' USING ERRCODE = 'check_violation';
    END IF;
    IF actor IS NULL OR NEW.created_by <> actor OR NEW.created_by <> owner_session.cashier_id THEN
        RAISE EXCEPTION 'a movement is written by the cashier of its session' USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(sum(CASE type WHEN 'deposit' THEN amount WHEN 'withdrawal' THEN -amount ELSE amount END), 0)
      INTO moved
      FROM cashbox_movement
     WHERE institution_id = NEW.institution_id AND session_id = NEW.session_id;
    NEW.expected_after := owner_session.opening_float + moved
        + CASE NEW.type WHEN 'deposit' THEN NEW.amount WHEN 'withdrawal' THEN -NEW.amount ELSE NEW.amount END;
    IF NEW.expected_after < 0 THEN
        RAISE EXCEPTION 'the expected amount of a session is never negative' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- INV-23: what a close proves when it is inserted. The expected amount is recomputed here from the rows.
CREATE FUNCTION cashbox_session_close_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    owner_session cashbox_session%ROWTYPE;
    actor         uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
    moved         numeric;
BEGIN
    SELECT * INTO owner_session FROM cashbox_session
        WHERE institution_id = NEW.institution_id AND id = NEW.session_id FOR UPDATE;
    IF NOT FOUND OR owner_session.status <> 'open' THEN
        RAISE EXCEPTION 'only an open session is closed' USING ERRCODE = 'check_violation';
    END IF;
    IF actor IS NULL OR NEW.closed_by <> actor OR owner_session.cashier_id <> NEW.closed_by
            OR owner_session.currency <> NEW.currency THEN
        RAISE EXCEPTION 'a session is closed by its cashier, in its currency' USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(sum(CASE type WHEN 'deposit' THEN amount WHEN 'withdrawal' THEN -amount ELSE amount END), 0)
      INTO moved
      FROM cashbox_movement
     WHERE institution_id = NEW.institution_id AND session_id = NEW.session_id;
    IF NEW.expected_amount <> owner_session.opening_float + moved THEN
        RAISE EXCEPTION 'the expected amount is the opening float plus the movements of the session'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.expected_amount < 0 THEN
        RAISE EXCEPTION 'the expected amount of a session is never negative' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- Segregation of duties: the approver is never the cashier, is the actor of the transaction, and only a pending close is
-- approved.
CREATE FUNCTION cashbox_session_approval_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    owner_session cashbox_session%ROWTYPE;
    actor         uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
BEGIN
    SELECT * INTO owner_session FROM cashbox_session
        WHERE institution_id = NEW.institution_id AND id = NEW.session_id FOR UPDATE;
    IF NOT FOUND OR owner_session.status <> 'pending_approval' THEN
        RAISE EXCEPTION 'only a session pending approval is approved' USING ERRCODE = 'check_violation';
    END IF;
    IF actor IS NULL OR NEW.approved_by <> actor THEN
        RAISE EXCEPTION 'an approval is written by its approver' USING ERRCODE = 'check_violation';
    END IF;
    IF owner_session.cashier_id = NEW.approved_by THEN
        RAISE EXCEPTION 'the approver of a close is never its cashier' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- The status moves forward only, and only with the record that justifies the move. Everything else is immutable.
CREATE FUNCTION cashbox_session_status_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    difference numeric;
BEGIN
    IF NEW.institution_id <> OLD.institution_id OR NEW.id <> OLD.id OR NEW.cashier_id <> OLD.cashier_id
            OR NEW.currency <> OLD.currency OR NEW.opening_float <> OLD.opening_float
            OR NEW.opened_at <> OLD.opened_at OR NEW.request_key <> OLD.request_key
            OR NEW.request_hash <> OLD.request_hash THEN
        RAISE EXCEPTION 'only the status of a cash session changes' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'open' AND NEW.status IN ('closed', 'pending_approval') THEN
        SELECT difference_amount INTO difference FROM cashbox_session_close
            WHERE institution_id = NEW.institution_id AND session_id = NEW.id;
        IF NOT FOUND OR (difference = 0) <> (NEW.status = 'closed') THEN
            RAISE EXCEPTION 'a session leaves open with its close record and the status that its difference asks'
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF OLD.status = 'pending_approval' AND NEW.status = 'closed' THEN
        IF NOT EXISTS (SELECT 1 FROM cashbox_session_approval
                WHERE institution_id = NEW.institution_id AND session_id = NEW.id) THEN
            RAISE EXCEPTION 'a session pending approval closes with its approval record'
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSE
        RAISE EXCEPTION 'a cash session status only moves forward: % to % is refused', OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION cashbox_session_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION cashbox_movement_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION cashbox_session_close_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION cashbox_session_approval_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION cashbox_session_status_guard() FROM PUBLIC;

CREATE TRIGGER cashbox_session_before_insert_trg
    BEFORE INSERT ON cashbox_session
    FOR EACH ROW EXECUTE FUNCTION cashbox_session_before_insert();
CREATE TRIGGER cashbox_movement_before_insert_trg
    BEFORE INSERT ON cashbox_movement
    FOR EACH ROW EXECUTE FUNCTION cashbox_movement_before_insert();
CREATE TRIGGER cashbox_session_close_before_insert_trg
    BEFORE INSERT ON cashbox_session_close
    FOR EACH ROW EXECUTE FUNCTION cashbox_session_close_before_insert();
CREATE TRIGGER cashbox_session_approval_before_insert_trg
    BEFORE INSERT ON cashbox_session_approval
    FOR EACH ROW EXECUTE FUNCTION cashbox_session_approval_before_insert();
CREATE TRIGGER cashbox_session_status_guard_trg
    BEFORE UPDATE ON cashbox_session
    FOR EACH ROW EXECUTE FUNCTION cashbox_session_status_guard();

CREATE TRIGGER cashbox_session_no_delete_trg
    BEFORE DELETE ON cashbox_session
    FOR EACH ROW EXECUTE FUNCTION cashbox_append_only_guard();
CREATE TRIGGER cashbox_session_truncate_trg
    BEFORE TRUNCATE ON cashbox_session
    FOR EACH STATEMENT EXECUTE FUNCTION cashbox_append_only_guard();
CREATE TRIGGER cashbox_movement_append_only_trg
    BEFORE UPDATE OR DELETE ON cashbox_movement
    FOR EACH ROW EXECUTE FUNCTION cashbox_append_only_guard();
CREATE TRIGGER cashbox_movement_truncate_trg
    BEFORE TRUNCATE ON cashbox_movement
    FOR EACH STATEMENT EXECUTE FUNCTION cashbox_append_only_guard();
CREATE TRIGGER cashbox_session_close_append_only_trg
    BEFORE UPDATE OR DELETE ON cashbox_session_close
    FOR EACH ROW EXECUTE FUNCTION cashbox_append_only_guard();
CREATE TRIGGER cashbox_session_close_truncate_trg
    BEFORE TRUNCATE ON cashbox_session_close
    FOR EACH STATEMENT EXECUTE FUNCTION cashbox_append_only_guard();
CREATE TRIGGER cashbox_session_approval_append_only_trg
    BEFORE UPDATE OR DELETE ON cashbox_session_approval
    FOR EACH ROW EXECUTE FUNCTION cashbox_append_only_guard();
CREATE TRIGGER cashbox_session_approval_truncate_trg
    BEFORE TRUNCATE ON cashbox_session_approval
    FOR EACH STATEMENT EXECUTE FUNCTION cashbox_append_only_guard();

-- ---------------------------------------------------------------------------------------------------------------------
-- Row-level security and privileges
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE cashbox_session          ENABLE ROW LEVEL SECURITY;
ALTER TABLE cashbox_session          FORCE  ROW LEVEL SECURITY;
ALTER TABLE cashbox_movement         ENABLE ROW LEVEL SECURITY;
ALTER TABLE cashbox_movement         FORCE  ROW LEVEL SECURITY;
ALTER TABLE cashbox_session_close    ENABLE ROW LEVEL SECURITY;
ALTER TABLE cashbox_session_close    FORCE  ROW LEVEL SECURITY;
ALTER TABLE cashbox_session_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE cashbox_session_approval FORCE  ROW LEVEL SECURITY;

CREATE POLICY cashbox_session_institution_isolation ON cashbox_session
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY cashbox_movement_institution_isolation ON cashbox_movement
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY cashbox_session_close_institution_isolation ON cashbox_session_close
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY cashbox_session_approval_institution_isolation ON cashbox_session_approval
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON cashbox_session          FROM PUBLIC;
REVOKE ALL ON cashbox_movement         FROM PUBLIC;
REVOKE ALL ON cashbox_session_close    FROM PUBLIC;
REVOKE ALL ON cashbox_session_approval FROM PUBLIC;

GRANT SELECT, INSERT ON cashbox_session          TO confia_admin_app;
GRANT UPDATE (status) ON cashbox_session         TO confia_admin_app;
GRANT SELECT, INSERT ON cashbox_movement         TO confia_admin_app;
GRANT SELECT, INSERT ON cashbox_session_close    TO confia_admin_app;
GRANT SELECT, INSERT ON cashbox_session_approval TO confia_admin_app;
GRANT SELECT ON cashbox_session          TO confia_readonly;
GRANT SELECT ON cashbox_movement         TO confia_readonly;
GRANT SELECT ON cashbox_session_close    TO confia_readonly;
GRANT SELECT ON cashbox_session_approval TO confia_readonly;
-- confia_portal_app: no grant, staff data (docs/03-seguridad.md section 9). confia_owner: schema owner, without BYPASSRLS, and
-- bound by every trigger above.
