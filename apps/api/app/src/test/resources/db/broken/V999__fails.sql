-- Test-only migration that always fails: it proves the migrate process exits with a non-zero
-- code and names the script (CR06). Never on the production location (classpath:db/migration);
-- MigrateProfileIT adds classpath:db/broken explicitly.
SELECT 1 / 0;
