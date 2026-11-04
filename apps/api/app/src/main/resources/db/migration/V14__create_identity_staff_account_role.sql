-- Fourteenth migration (F0 change 8, slice 2; rbac-permission-matrix-and-audit-integration design.md
-- decision D4; owner decisions O1 and O3). Creates the one role of a staff account within its
-- institution. The catalog of roles and permissions lives in code (com.confia.identity.domain.StaffRole),
-- so no global table exists and the closed list of ADR-0017 does not change.

CREATE TABLE identity_staff_account_role (
    institution_id UUID        NOT NULL,
    account_id     UUID        NOT NULL,
    role_code      TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    -- One role per account and institution: a second assignment is refused by the database itself.
    CONSTRAINT identity_staff_account_role_pk         PRIMARY KEY (institution_id, account_id),
    -- The account must exist in the same institution, so an assignment never crosses institutions.
    CONSTRAINT identity_staff_account_role_account_fk FOREIGN KEY (institution_id, account_id)
        REFERENCES identity_staff_account (institution_id, id),
    -- Closed set of the six roles of StaffRole; IdentityStaffAccountRoleSchemaIT compares it with the enum.
    CONSTRAINT identity_staff_account_role_code_chk   CHECK (role_code IN ('super_admin',
        'administrator', 'cashier', 'accounting', 'auditor', 'academic_coordinator'))
);

ALTER TABLE identity_staff_account_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_staff_account_role FORCE  ROW LEVEL SECURITY;

CREATE POLICY identity_staff_account_role_institution_isolation ON identity_staff_account_role
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON identity_staff_account_role FROM PUBLIC;

-- SELECT only: nothing in production writes this table yet. Change 8b, which adds the use case that
-- assigns roles, grants what it needs in its own migration.
GRANT SELECT ON identity_staff_account_role TO confia_admin_app;
GRANT SELECT ON identity_staff_account_role TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.
-- confia_backup: reads through pg_read_all_data, which never bypasses row-level security.
