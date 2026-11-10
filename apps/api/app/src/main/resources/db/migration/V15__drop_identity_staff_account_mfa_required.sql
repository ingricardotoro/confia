-- Fifteenth migration (F0 change 8, slice 5b). The second factor is decided by MfaPolicy since
-- slice 5a, and nothing reads this column any more (owner decisions O4 and Q1).
ALTER TABLE identity_staff_account DROP COLUMN mfa_required;
