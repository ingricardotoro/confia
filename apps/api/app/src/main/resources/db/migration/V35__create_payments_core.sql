-- Thirty-fifth migration (F4, cut 2; docs/cortes/payments-register.md). Creates the payments of the students: a cash payment, born
-- confirmed, and a declared bank transfer that stays outside the ledger until a person authorized confirms or rejects it. Every
-- confirmed payment is posted to the ledger (kind PAYMENT) and allocated to the open charges of the student, oldest first.
-- It also lets the cash movements of cashbox carry the new type payment_in.
--
-- THE DATABASE REFUSES, to every role including the owner:
--   * (INV-19) a payment whose amount is not greater than zero or has more digits than the minor unit;
--   * (INV-20) a CASH payment that does not name the OPEN session of the cashier who registers it, together with a movement
--     payment_in of that session for the same amount, written by the same cashier, and that is not dated the business day of
--     the session, that is today in America/Tegucigalpa (a cash receipt is never back-dated); a payment_in movement that no
--     payment claims by the end of the transaction (deferred constraint trigger on cashbox_movement);
--   * a payment, a confirmation or a posting that is not a ledger transaction of kind PAYMENT, from the module payments, that
--     names this payment, dated the day it was received for cash and for a confirmation, whose debits equal the amount and
--     whose credits to the receivable go to the sub-account of the student of the payment;
--   * (INV-17) an allocation that makes the sum allocated to a charge exceed the total of the charge, an allocation to a charge
--     of another student, in another currency or already voided; the trigger takes the lock of the student sub-account of the
--     ledger before it sums, so two simultaneous payments serialize;
--   * (INV-18) an allocation that makes the sum allocated by a payment exceed its amount, or one for a payment that is not
--     confirmed;
--   * a confirmation or a rejection of a payment that is not a declared transfer, by anyone but the actor of the transaction,
--     and by the same person who registered it (segregation of duties); a decision whose kind does not match the status the
--     payment holds at COMMIT (deferred constraint trigger: a confirmed decision needs a confirmed payment, a rejection a
--     rejected one, so a decision cannot be written without the status move);
--   * a second transfer of the same bank and reference among the payments that were not rejected;
--   * the void of a charge that has allocations (INV-47 keeps the rest of the charge immutable); the guard takes the lock of the
--     student sub-account first, the same lock and order as the allocation trigger, so a void and an allocation take turns;
--   * an UPDATE of a payment other than the advance of its status along declared -> confirmed | rejected, and only together with
--     the decision that justifies it; a DELETE or a TRUNCATE of anything; an UPDATE of a decision or an allocation.
-- Nothing financial is edited: the payment, its decision and its allocations are insert-only rows. The status of the payment is the
-- only mutable column, it moves forward once, and the trigger proves the record behind the move. The balance of a student, and
-- what is outstanding in a charge, are DERIVED (ledger entries, charge total minus allocations): no column holds them.
--
-- Trigger functions are SECURITY INVOKER with a fixed search_path and no PUBLIC execute. The institution comes from the session,
-- never from a request: forced row-level security keeps one institution from reading or writing another.

CREATE FUNCTION payments_append_only_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    RAISE EXCEPTION 'payments records are append-only: % on % is refused', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

REVOKE ALL ON FUNCTION payments_append_only_guard() FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------------------------------
-- cashbox: the movement that a cash payment leaves in the session of the cashier
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE cashbox_movement DROP CONSTRAINT cashbox_movement_type_chk;
ALTER TABLE cashbox_movement ADD CONSTRAINT cashbox_movement_type_chk
    CHECK (type IN ('deposit', 'withdrawal', 'adjustment', 'payment_in'));
ALTER TABLE cashbox_movement DROP CONSTRAINT cashbox_movement_amount_chk;
ALTER TABLE cashbox_movement ADD CONSTRAINT cashbox_movement_amount_chk CHECK (
    (type IN ('deposit', 'withdrawal', 'payment_in') AND amount > 0) OR (type = 'adjustment' AND amount <> 0));

-- ---------------------------------------------------------------------------------------------------------------------
-- Posting accounts of payments: the cash account and the bank account of the chart. DATA, configured per institution.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE payments_posting_account (
    institution_id    UUID        NOT NULL,
    id                UUID        NOT NULL,
    purpose           TEXT        NOT NULL,
    ledger_account_id UUID        NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT payments_posting_account_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT payments_posting_account_account_fk FOREIGN KEY (institution_id, ledger_account_id)
        REFERENCES ledger_account (institution_id, id),
    CONSTRAINT payments_posting_account_purpose_chk CHECK (purpose IN ('CASH', 'BANK')),
    CONSTRAINT payments_posting_account_version_chk CHECK (version >= 0),
    -- One account per purpose.
    CONSTRAINT payments_posting_account_purpose_uq UNIQUE (institution_id, purpose)
);

CREATE INDEX payments_posting_account_account_idx ON payments_posting_account (institution_id, ledger_account_id);

-- ---------------------------------------------------------------------------------------------------------------------
-- Payments: INSERT-ONLY apart from the forward move of the status
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE payments_payment (
    institution_id        UUID          NOT NULL,
    id                    UUID          NOT NULL,
    student_id            UUID          NOT NULL,
    guardian_id           UUID,
    method                TEXT          NOT NULL,
    status                TEXT          NOT NULL,
    amount                NUMERIC(14,4) NOT NULL,
    currency              CHAR(3)       NOT NULL,
    received_on           DATE          NOT NULL,
    reference             VARCHAR(40),
    bank_name             VARCHAR(80),
    notes                 TEXT,
    -- A cash payment names the session and the movement payment_in it left; a transfer names neither.
    cash_session_id       UUID,
    cash_movement_id      UUID,
    -- The posting of a CASH payment (born confirmed). The posting of a transfer lives in its decision.
    ledger_transaction_id UUID,
    registered_by         UUID          NOT NULL,
    registered_at         TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    -- The Idempotency-Key of the request that registered it, PERMANENT, with the hash of what it asked.
    request_key           VARCHAR(128)  NOT NULL,
    request_hash          CHAR(64)      NOT NULL,
    CONSTRAINT payments_payment_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT payments_payment_student_fk FOREIGN KEY (institution_id, student_id)
        REFERENCES students_student (institution_id, id),
    CONSTRAINT payments_payment_guardian_fk FOREIGN KEY (institution_id, guardian_id)
        REFERENCES guardians_guardian (institution_id, id),
    CONSTRAINT payments_payment_session_fk FOREIGN KEY (institution_id, cash_session_id)
        REFERENCES cashbox_session (institution_id, id),
    CONSTRAINT payments_payment_movement_fk FOREIGN KEY (institution_id, cash_movement_id)
        REFERENCES cashbox_movement (institution_id, id),
    CONSTRAINT payments_payment_ledger_fk FOREIGN KEY (institution_id, ledger_transaction_id)
        REFERENCES ledger_transaction (institution_id, id),
    CONSTRAINT payments_payment_key_uq UNIQUE (institution_id, request_key),
    -- A movement and a posting belong to one payment.
    CONSTRAINT payments_payment_movement_uq UNIQUE (institution_id, cash_movement_id),
    CONSTRAINT payments_payment_ledger_uq UNIQUE (institution_id, ledger_transaction_id),
    CONSTRAINT payments_payment_method_chk CHECK (method IN ('CASH', 'BANK_TRANSFER')),
    CONSTRAINT payments_payment_status_chk CHECK (status IN ('declared', 'confirmed', 'rejected')),
    CONSTRAINT payments_payment_currency_chk CHECK (currency IN ('HNL', 'USD')),
    -- INV-19, and every amount of the ledger is in the minor unit of its currency (INV-40).
    CONSTRAINT payments_payment_amount_chk CHECK (amount > 0 AND amount = round(amount, 2)),
    CONSTRAINT payments_payment_received_chk CHECK (received_on BETWEEN date '2000-01-01' AND date '2100-12-31'),
    CONSTRAINT payments_payment_notes_chk CHECK (notes IS NULL OR (char_length(btrim(notes)) >= 1 AND char_length(notes) <= 500)),
    -- INV-20 and the shape of each method: cash is born confirmed with its session, movement and posting; a transfer carries the
    -- bank and the reference and touches no cash.
    CONSTRAINT payments_payment_shape_chk CHECK (
        (method = 'CASH' AND status = 'confirmed' AND cash_session_id IS NOT NULL AND cash_movement_id IS NOT NULL
            AND ledger_transaction_id IS NOT NULL AND reference IS NULL AND bank_name IS NULL)
        OR (method = 'BANK_TRANSFER' AND cash_session_id IS NULL AND cash_movement_id IS NULL
            AND ledger_transaction_id IS NULL AND reference IS NOT NULL AND bank_name IS NOT NULL
            AND char_length(btrim(reference)) BETWEEN 4 AND 40 AND char_length(reference) <= 40
            AND char_length(btrim(bank_name)) BETWEEN 1 AND 80 AND char_length(bank_name) <= 80)),
    CONSTRAINT payments_payment_key_chk CHECK (request_key ~ '^[!-~]{1,128}$'),
    CONSTRAINT payments_payment_hash_chk CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

-- The same bank and reference is not declared twice among the payments that were not rejected. Case does not matter.
CREATE UNIQUE INDEX payments_payment_transfer_ref_uq
    ON payments_payment (institution_id, lower(bank_name), lower(reference))
    WHERE method = 'BANK_TRANSFER' AND status <> 'rejected';
CREATE INDEX payments_payment_student_idx ON payments_payment (institution_id, student_id, registered_at DESC, id);
CREATE INDEX payments_payment_status_idx ON payments_payment (institution_id, status, received_on DESC, id);
CREATE INDEX payments_payment_registrar_idx ON payments_payment (institution_id, registered_by, registered_at DESC, id);

-- ---------------------------------------------------------------------------------------------------------------------
-- Decisions on a declared transfer: INSERT-ONLY. At most one per payment.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE payments_payment_decision (
    institution_id        UUID         NOT NULL,
    id                    UUID         NOT NULL,
    payment_id            UUID         NOT NULL,
    decision              TEXT         NOT NULL,
    -- Only a rejection carries a reason; it is free text that may name a person and is never copied to the ledger or the audit trail.
    reason                TEXT,
    -- Only a confirmation carries the posting.
    ledger_transaction_id UUID,
    decided_by            UUID         NOT NULL,
    decided_at            TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    request_key           VARCHAR(128) NOT NULL,
    request_hash          CHAR(64)     NOT NULL,
    CONSTRAINT payments_payment_decision_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT payments_payment_decision_payment_fk FOREIGN KEY (institution_id, payment_id)
        REFERENCES payments_payment (institution_id, id),
    CONSTRAINT payments_payment_decision_ledger_fk FOREIGN KEY (institution_id, ledger_transaction_id)
        REFERENCES ledger_transaction (institution_id, id),
    CONSTRAINT payments_payment_decision_payment_uq UNIQUE (institution_id, payment_id),
    CONSTRAINT payments_payment_decision_ledger_uq UNIQUE (institution_id, ledger_transaction_id),
    CONSTRAINT payments_payment_decision_key_uq UNIQUE (institution_id, request_key),
    CONSTRAINT payments_payment_decision_kind_chk CHECK (decision IN ('confirmed', 'rejected')),
    CONSTRAINT payments_payment_decision_shape_chk CHECK (
        (decision = 'confirmed' AND reason IS NULL AND ledger_transaction_id IS NOT NULL)
        OR (decision = 'rejected' AND ledger_transaction_id IS NULL AND reason IS NOT NULL
            AND char_length(btrim(reason)) BETWEEN 20 AND 500 AND char_length(reason) <= 500)),
    CONSTRAINT payments_payment_decision_key_chk CHECK (request_key ~ '^[!-~]{1,128}$'),
    CONSTRAINT payments_payment_decision_hash_chk CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

-- ---------------------------------------------------------------------------------------------------------------------
-- Allocations of a confirmed payment to the charges: INSERT-ONLY
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE payments_payment_allocation (
    institution_id UUID          NOT NULL,
    id             UUID          NOT NULL,
    payment_id     UUID          NOT NULL,
    charge_id      UUID          NOT NULL,
    amount         NUMERIC(14,4) NOT NULL,
    currency       CHAR(3)       NOT NULL,
    allocated_at   TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT payments_payment_allocation_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT payments_payment_allocation_payment_fk FOREIGN KEY (institution_id, payment_id)
        REFERENCES payments_payment (institution_id, id),
    CONSTRAINT payments_payment_allocation_charge_fk FOREIGN KEY (institution_id, charge_id)
        REFERENCES charges_charge (institution_id, id),
    -- A payment is allocated to a charge at most once.
    CONSTRAINT payments_payment_allocation_pair_uq UNIQUE (institution_id, payment_id, charge_id),
    CONSTRAINT payments_payment_allocation_currency_chk CHECK (currency IN ('HNL', 'USD')),
    CONSTRAINT payments_payment_allocation_amount_chk CHECK (amount > 0 AND amount = round(amount, 2))
);

CREATE INDEX payments_payment_allocation_charge_idx ON payments_payment_allocation (institution_id, charge_id)
    INCLUDE (amount, payment_id);

-- ---------------------------------------------------------------------------------------------------------------------
-- Trigger functions
-- ---------------------------------------------------------------------------------------------------------------------

-- The transaction of the ledger that posted a payment: kind PAYMENT, from payments, naming the payment, dated the day it was
-- received, whose debits equal its amount and whose credits to a student sub-account go to the sub-account of the student of the
-- payment for the whole amount. Used for a cash payment and for the confirmation of a transfer.
CREATE FUNCTION payments_require_posting(p_institution uuid, p_payment uuid, p_transaction uuid, p_amount numeric,
        p_received date, p_student uuid) RETURNS void
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    posted ledger_transaction%ROWTYPE;
    debits numeric;
    credits numeric;
BEGIN
    SELECT * INTO posted FROM ledger_transaction WHERE institution_id = p_institution AND id = p_transaction;
    IF NOT FOUND OR posted.kind <> 'PAYMENT' OR posted.source_module <> 'payments'
            OR posted.source_ref_id IS DISTINCT FROM p_payment OR posted.effective_on <> p_received THEN
        RAISE EXCEPTION 'a payment is posted by a PAYMENT transaction of payments that names it' USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(sum(amount), 0) INTO debits FROM ledger_entry
        WHERE institution_id = p_institution AND transaction_id = p_transaction AND side = 'DEBIT';
    IF debits <> p_amount THEN
        RAISE EXCEPTION 'the posting of a payment is for the amount of the payment' USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(sum(e.amount), 0) INTO credits FROM ledger_entry e
        JOIN ledger_student_account s ON s.institution_id = e.institution_id AND s.id = e.student_account_id
        WHERE e.institution_id = p_institution AND e.transaction_id = p_transaction AND e.side = 'CREDIT'
            AND s.student_id = p_student;
    IF credits <> p_amount THEN
        RAISE EXCEPTION 'the posting of a payment credits the sub-account of the student of the payment' USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

-- INV-19, INV-20 and the born state of each method. The registrar is the actor of the transaction, never a member of a statement.
CREATE FUNCTION payments_payment_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    actor   uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
    owner_session cashbox_session%ROWTYPE;
    moved   cashbox_movement%ROWTYPE;
BEGIN
    IF actor IS NULL OR NEW.registered_by <> actor THEN
        RAISE EXCEPTION 'a payment is registered by the actor of the transaction' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.registered_at NOT BETWEEN clock_timestamp() - interval '1 minute' AND clock_timestamp() + interval '1 minute' THEN
        RAISE EXCEPTION 'a payment is registered now' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.method = 'BANK_TRANSFER' THEN
        IF NEW.status <> 'declared' THEN
            RAISE EXCEPTION 'a transfer is born declared' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    -- A cash receipt is dated the business day of the session: today in the institution time zone, never back-dated (the same
    -- zone the application uses; a session is of one business day).
    IF NEW.received_on <> (clock_timestamp() AT TIME ZONE 'America/Tegucigalpa')::date THEN
        RAISE EXCEPTION 'a cash payment is received on the business day of the open session' USING ERRCODE = 'check_violation';
    END IF;
    -- CASH (INV-20): the open session of the cashier who registers, and the payment_in movement of that session.
    SELECT * INTO owner_session FROM cashbox_session
        WHERE institution_id = NEW.institution_id AND id = NEW.cash_session_id;
    IF NOT FOUND OR owner_session.status <> 'open' OR owner_session.cashier_id <> NEW.registered_by
            OR owner_session.currency <> NEW.currency THEN
        RAISE EXCEPTION 'cash is collected in the open session of the cashier who registers the payment'
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO moved FROM cashbox_movement
        WHERE institution_id = NEW.institution_id AND id = NEW.cash_movement_id;
    IF NOT FOUND OR moved.type <> 'payment_in' OR moved.session_id <> NEW.cash_session_id OR moved.amount <> NEW.amount
            OR moved.currency <> NEW.currency OR moved.created_by <> NEW.registered_by THEN
        RAISE EXCEPTION 'a cash payment leaves one payment_in movement of the same amount in its session'
            USING ERRCODE = 'check_violation';
    END IF;
    PERFORM payments_require_posting(NEW.institution_id, NEW.id, NEW.ledger_transaction_id, NEW.amount, NEW.received_on,
        NEW.student_id);
    RETURN NEW;
END;
$$;

-- The status moves forward once, declared to confirmed or rejected, with the decision that justifies it. Nothing else changes.
CREATE FUNCTION payments_payment_status_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    decided text;
    expected text := NEW.status;
BEGIN
    IF (NEW.institution_id, NEW.id, NEW.student_id, NEW.guardian_id, NEW.method, NEW.amount, NEW.currency, NEW.received_on,
            NEW.reference, NEW.bank_name, NEW.notes, NEW.cash_session_id, NEW.cash_movement_id, NEW.ledger_transaction_id,
            NEW.registered_by, NEW.registered_at, NEW.request_key, NEW.request_hash)
        IS DISTINCT FROM
       (OLD.institution_id, OLD.id, OLD.student_id, OLD.guardian_id, OLD.method, OLD.amount, OLD.currency, OLD.received_on,
            OLD.reference, OLD.bank_name, OLD.notes, OLD.cash_session_id, OLD.cash_movement_id, OLD.ledger_transaction_id,
            OLD.registered_by, OLD.registered_at, OLD.request_key, OLD.request_hash) THEN
        RAISE EXCEPTION 'only the status of a payment changes' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status <> 'declared' OR NEW.status NOT IN ('confirmed', 'rejected') THEN
        RAISE EXCEPTION 'a payment status moves forward from declared only: % to % is refused', OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    SELECT decision INTO decided FROM payments_payment_decision
        WHERE institution_id = NEW.institution_id AND payment_id = NEW.id;
    IF NOT FOUND OR decided <> expected THEN
        RAISE EXCEPTION 'a payment leaves declared with the decision that its new status asks' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- A decision decides a declared transfer, once, by the actor of the transaction, who is never the one who registered it.
CREATE FUNCTION payments_payment_decision_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    actor   uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
    decided payments_payment%ROWTYPE;
BEGIN
    SELECT * INTO decided FROM payments_payment
        WHERE institution_id = NEW.institution_id AND id = NEW.payment_id FOR UPDATE;
    IF NOT FOUND OR decided.status <> 'declared' OR decided.method <> 'BANK_TRANSFER' THEN
        RAISE EXCEPTION 'only a declared transfer is decided' USING ERRCODE = 'check_violation';
    END IF;
    IF actor IS NULL OR NEW.decided_by <> actor THEN
        RAISE EXCEPTION 'a decision is written by its author' USING ERRCODE = 'check_violation';
    END IF;
    IF decided.registered_by = NEW.decided_by THEN
        RAISE EXCEPTION 'the one who registers a payment never decides it' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.decided_at NOT BETWEEN clock_timestamp() - interval '1 minute' AND clock_timestamp() + interval '1 minute' THEN
        RAISE EXCEPTION 'a decision is made now' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.decision = 'confirmed' THEN
        PERFORM payments_require_posting(NEW.institution_id, NEW.payment_id, NEW.ledger_transaction_id, decided.amount,
            decided.received_on, decided.student_id);
    END IF;
    RETURN NEW;
END;
$$;

-- INV-17 and INV-18, whoever writes the allocation. The sub-account of the ledger is locked FOR UPDATE before anything is summed,
-- the same lock every writer of the charges of the student takes first, so two payments of the same student take turns.
CREATE FUNCTION payments_allocation_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    paid       payments_payment%ROWTYPE;
    owed       charges_charge%ROWTYPE;
    taken      numeric;
    paid_so_far numeric;
BEGIN
    SELECT * INTO paid FROM payments_payment
        WHERE institution_id = NEW.institution_id AND id = NEW.payment_id;
    IF NOT FOUND OR paid.status <> 'confirmed' THEN
        RAISE EXCEPTION 'only a confirmed payment is allocated' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO owed FROM charges_charge WHERE institution_id = NEW.institution_id AND id = NEW.charge_id;
    IF NOT FOUND OR owed.student_id <> paid.student_id OR owed.total_currency <> NEW.currency
            OR paid.currency <> NEW.currency THEN
        RAISE EXCEPTION 'a payment is allocated to a charge of its own student, in its own currency'
            USING ERRCODE = 'check_violation';
    END IF;
    PERFORM 1 FROM ledger_student_account
        WHERE institution_id = NEW.institution_id AND student_id = paid.student_id AND currency = NEW.currency FOR UPDATE;
    IF EXISTS (SELECT 1 FROM charges_charge_void WHERE institution_id = NEW.institution_id AND charge_id = NEW.charge_id) THEN
        RAISE EXCEPTION 'a voided charge receives no allocation' USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(sum(amount), 0) INTO taken FROM payments_payment_allocation
        WHERE institution_id = NEW.institution_id AND charge_id = NEW.charge_id;
    IF taken + NEW.amount > owed.total_amount THEN
        RAISE EXCEPTION 'the allocations of a charge never exceed its total' USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(sum(amount), 0) INTO paid_so_far FROM payments_payment_allocation
        WHERE institution_id = NEW.institution_id AND payment_id = NEW.payment_id;
    IF paid_so_far + NEW.amount > paid.amount THEN
        RAISE EXCEPTION 'the allocations of a payment never exceed its amount' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- A charge with allocations is not voided: the payment is reversed first (docs/cortes/payments-register.md).
-- The lock of the student sub-account comes BEFORE the existence check, the same lock in the same order as the allocation trigger
-- (payments_allocation_before_insert): a void and an allocation of the same student take turns, so a void cannot commit while an
-- allocation to its charge is in flight, nor the reverse. charges_charge grants no UPDATE (INV-47), so this is the serialization.
CREATE FUNCTION payments_charge_void_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    voided charges_charge%ROWTYPE;
BEGIN
    SELECT * INTO voided FROM charges_charge WHERE institution_id = NEW.institution_id AND id = NEW.charge_id;
    IF FOUND THEN
        PERFORM 1 FROM ledger_student_account
            WHERE institution_id = NEW.institution_id AND student_id = voided.student_id
                AND currency = voided.total_currency FOR UPDATE;
    END IF;
    IF EXISTS (SELECT 1 FROM payments_payment_allocation
                WHERE institution_id = NEW.institution_id AND charge_id = NEW.charge_id) THEN
        RAISE EXCEPTION 'a charge with payments is not voided' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- A decision agrees with the status of its payment at COMMIT: a confirmation needs a confirmed payment and a rejection a rejected
-- one. The status moves in a statement after the decision is inserted, so this is a deferred constraint trigger: a decision
-- written without the move of the status, by any role, never commits.
CREATE FUNCTION payments_payment_decision_has_status() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM payments_payment
            WHERE institution_id = NEW.institution_id AND id = NEW.payment_id AND status = NEW.decision) THEN
        RAISE EXCEPTION 'a decision is committed with the status of its payment moved to the same outcome'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

-- A payment_in movement belongs to a payment: checked at COMMIT, because the movement is inserted before the payment that names it.
CREATE FUNCTION payments_payment_in_has_payment() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.type = 'payment_in' AND NOT EXISTS (SELECT 1 FROM payments_payment
            WHERE institution_id = NEW.institution_id AND cash_movement_id = NEW.id) THEN
        RAISE EXCEPTION 'a payment_in movement is claimed by a payment' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION payments_posting_account_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a posting account is never deleted, it is replaced' USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.institution_id, NEW.id, NEW.purpose, NEW.created_at)
            IS DISTINCT FROM (OLD.institution_id, OLD.id, OLD.purpose, OLD.created_at) THEN
        RAISE EXCEPTION 'the purpose of a posting account never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION payments_require_posting(uuid, uuid, uuid, numeric, date, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION payments_payment_decision_has_status() FROM PUBLIC;
REVOKE ALL ON FUNCTION payments_payment_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION payments_payment_status_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION payments_payment_decision_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION payments_allocation_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION payments_charge_void_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION payments_payment_in_has_payment() FROM PUBLIC;
REVOKE ALL ON FUNCTION payments_posting_account_guard() FROM PUBLIC;

CREATE TRIGGER payments_payment_before_insert_trg
    BEFORE INSERT ON payments_payment
    FOR EACH ROW EXECUTE FUNCTION payments_payment_before_insert();
CREATE TRIGGER payments_payment_status_guard_trg
    BEFORE UPDATE ON payments_payment
    FOR EACH ROW EXECUTE FUNCTION payments_payment_status_guard();
CREATE TRIGGER payments_payment_no_delete_trg
    BEFORE DELETE ON payments_payment
    FOR EACH ROW EXECUTE FUNCTION payments_append_only_guard();
CREATE TRIGGER payments_payment_truncate_trg
    BEFORE TRUNCATE ON payments_payment
    FOR EACH STATEMENT EXECUTE FUNCTION payments_append_only_guard();

CREATE TRIGGER payments_payment_decision_before_insert_trg
    BEFORE INSERT ON payments_payment_decision
    FOR EACH ROW EXECUTE FUNCTION payments_payment_decision_before_insert();
CREATE TRIGGER payments_payment_decision_append_only_trg
    BEFORE UPDATE OR DELETE ON payments_payment_decision
    FOR EACH ROW EXECUTE FUNCTION payments_append_only_guard();
CREATE TRIGGER payments_payment_decision_truncate_trg
    BEFORE TRUNCATE ON payments_payment_decision
    FOR EACH STATEMENT EXECUTE FUNCTION payments_append_only_guard();

CREATE TRIGGER payments_payment_allocation_before_insert_trg
    BEFORE INSERT ON payments_payment_allocation
    FOR EACH ROW EXECUTE FUNCTION payments_allocation_before_insert();
CREATE TRIGGER payments_payment_allocation_append_only_trg
    BEFORE UPDATE OR DELETE ON payments_payment_allocation
    FOR EACH ROW EXECUTE FUNCTION payments_append_only_guard();
CREATE TRIGGER payments_payment_allocation_truncate_trg
    BEFORE TRUNCATE ON payments_payment_allocation
    FOR EACH STATEMENT EXECUTE FUNCTION payments_append_only_guard();

CREATE TRIGGER payments_posting_account_guard_trg
    BEFORE UPDATE OR DELETE ON payments_posting_account
    FOR EACH ROW EXECUTE FUNCTION payments_posting_account_guard();
CREATE TRIGGER payments_posting_account_truncate_trg
    BEFORE TRUNCATE ON payments_posting_account
    FOR EACH STATEMENT EXECUTE FUNCTION payments_append_only_guard();

-- The charge void and the payment_in movement are tables of other modules; the guards that protect payments live here.
CREATE TRIGGER payments_charge_void_guard_trg
    BEFORE INSERT ON charges_charge_void
    FOR EACH ROW EXECUTE FUNCTION payments_charge_void_guard();
CREATE CONSTRAINT TRIGGER payments_payment_in_has_payment_trg
    AFTER INSERT ON cashbox_movement
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payments_payment_in_has_payment();
CREATE CONSTRAINT TRIGGER payments_payment_decision_has_status_trg
    AFTER INSERT ON payments_payment_decision
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payments_payment_decision_has_status();

-- ---------------------------------------------------------------------------------------------------------------------
-- Row-level security and privileges
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE payments_posting_account    ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments_posting_account    FORCE  ROW LEVEL SECURITY;
ALTER TABLE payments_payment            ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments_payment            FORCE  ROW LEVEL SECURITY;
ALTER TABLE payments_payment_decision   ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments_payment_decision   FORCE  ROW LEVEL SECURITY;
ALTER TABLE payments_payment_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments_payment_allocation FORCE  ROW LEVEL SECURITY;

CREATE POLICY payments_posting_account_institution_isolation ON payments_posting_account
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY payments_payment_institution_isolation ON payments_payment
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY payments_payment_decision_institution_isolation ON payments_payment_decision
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY payments_payment_allocation_institution_isolation ON payments_payment_allocation
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON payments_posting_account    FROM PUBLIC;
REVOKE ALL ON payments_payment            FROM PUBLIC;
REVOKE ALL ON payments_payment_decision   FROM PUBLIC;
REVOKE ALL ON payments_payment_allocation FROM PUBLIC;

-- The posting proof is called by the triggers above on behalf of the application role, which therefore needs to execute it.
GRANT EXECUTE ON FUNCTION payments_require_posting(uuid, uuid, uuid, numeric, date, uuid) TO confia_admin_app;

GRANT SELECT, INSERT ON payments_posting_account TO confia_admin_app;
GRANT UPDATE (ledger_account_id, version, updated_at) ON payments_posting_account TO confia_admin_app;
GRANT SELECT, INSERT ON payments_payment TO confia_admin_app;
GRANT UPDATE (status) ON payments_payment TO confia_admin_app;
GRANT SELECT, INSERT ON payments_payment_decision   TO confia_admin_app;
GRANT SELECT, INSERT ON payments_payment_allocation TO confia_admin_app;
GRANT SELECT ON payments_posting_account    TO confia_readonly;
GRANT SELECT ON payments_payment            TO confia_readonly;
GRANT SELECT ON payments_payment_decision   TO confia_readonly;
GRANT SELECT ON payments_payment_allocation TO confia_readonly;
-- confia_portal_app: no grant, staff data (docs/03-seguridad.md section 9). confia_owner: schema owner, without BYPASSRLS, and
-- bound by every trigger above.
