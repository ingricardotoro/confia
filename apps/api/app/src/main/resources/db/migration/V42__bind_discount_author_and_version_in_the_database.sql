-- scholarships-discounts, security recheck advisories A1 and A2 (docs/cortes/scholarships-discounts.md).
--
-- A1. THE AUTHOR OF A DISCOUNT IS THE PERSON OF THE SESSION. The database refuses a campaign discount approved by the person who
--     created it (V41), and it learns who created it from created_by. Until now nothing bound created_by to the session on insert,
--     so a direct writer could create a discount in the name of a third account and approve it alone. Same rule as the requester of
--     an assignment or an award (V41, finding L1).
--
-- A2. EVERY CHANGE OF A DISCOUNT TAKES A NEW VERSION. An assignment is approved only while the discount keeps the version it was
--     requested for (V41, finding H2), and a campaign decision is bound to the version the decider saw. That binding holds only if
--     every change moves the version by exactly one, as the application already does.
--
-- Both run AFTER the row is written, so the checks of V39 to V41 (and the constraints and row-level security) keep answering first
-- with their own reasons. No stored row changes.

CREATE FUNCTION scholarships_discount_author_and_version_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    actor uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF actor IS NULL OR NEW.created_by <> actor THEN
            RAISE EXCEPTION 'a discount is created by the person of the session'
                USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_created_by_chk';
        END IF;
    ELSIF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'every change of a discount takes the next version'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_version_chk';
    END IF;
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION scholarships_discount_author_and_version_guard() FROM PUBLIC;

CREATE TRIGGER scholarships_discount_author_and_version_trg
    AFTER INSERT OR UPDATE ON scholarships_discount
    FOR EACH ROW EXECUTE FUNCTION scholarships_discount_author_and_version_guard();
