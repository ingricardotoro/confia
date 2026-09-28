package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.support.TransactionalPostgresIntegrationTest;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

/**
 * Schema catalogue gates over the real PostgreSQL schema created in PR A2 (design.md decision 10,
 * points 1 to 4; specs/build-integrity/spec.md). Every assertion here queries {@code pg_catalog}
 * directly — never {@code information_schema}, whose views hide any table the connecting role
 * holds no privilege on, and never a hand-maintained list of table names — so a new table added
 * later without the required invariant breaks this build without anyone having to remember to
 * extend this class.
 *
 * <p>Assertions are membership-based against ADR-0017's closed catalogue, never presence-based
 * (tasks.md task 3.5, point 1): only two of the four closed-catalogue tables exist today (
 * {@code flyway_schema_history} and the root table {@code organization_institution}); {@code
 * scheduled_tasks} and {@code event_publication} arrive with change 9. A table belongs to the
 * catalogue only by exact name match, never by a heuristic such as "looks technical".
 */
class MultiTenantSchemaIT extends TransactionalPostgresIntegrationTest {

    private static final String ROOT_TABLE = "organization_institution";

    /** ADR-0017's three purely technical tables — no {@code institution_id}, no module prefix. */
    private static final Set<String> TECHNICAL_TABLES =
            Set.of("scheduled_tasks", "event_publication", "flyway_schema_history");

    /** ADR-0017's closed catalogue of four exact names, the root table included. */
    private static final Set<String> CLOSED_CATALOGUE_TABLES =
            Set.of("scheduled_tasks", "event_publication", "flyway_schema_history", ROOT_TABLE);

    private static final String BASE_PACKAGE_PREFIX = "com.confia.";

    @Test
    void everyBusinessTableHasInstitutionIdOrBelongsToTheClosedCatalogueByExactName() {
        List<BaseTable> tables = baseTablesInPublicSchema();
        for (BaseTable table : tables) {
            assertThat(table.hasNotNullInstitutionId()
                    || CLOSED_CATALOGUE_TABLES.contains(table.name()))
                    .as("table %s must have institution_id NOT NULL or belong to the closed "
                            + "catalogue by exact name", table.name())
                    .isTrue();
        }
        // Membership, not presence (task 3.5, point 1): confirms the loop above actually ran
        // over real rows, without asserting that all four catalogue tables exist yet.
        assertThat(tables).extracting(BaseTable::name)
                .contains(ROOT_TABLE, "flyway_schema_history");
    }

    @Test
    void everyTableWithInstitutionIdAndTheRootTableHaveRowLevelSecurityEnabledAndForced() {
        for (BaseTable table : baseTablesInPublicSchema()) {
            if (!table.hasNotNullInstitutionId() && !table.name().equals(ROOT_TABLE)) {
                continue;
            }
            RowSecurityFlags flags = rowSecurityFlagsOf(table.name());
            assertThat(flags.rowSecurity())
                    .as("%s must have ENABLE ROW LEVEL SECURITY", table.name()).isTrue();
            assertThat(flags.forceRowSecurity())
                    .as("%s must have FORCE ROW LEVEL SECURITY", table.name()).isTrue();
        }
        assertThat(policyCountOn(ROOT_TABLE))
                .as("the root table must have at least one policy on its primary key")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void everyUniqueIndexOfABusinessTableIncludesTheInstitutionDiscriminator() {
        for (UniqueIndex index : uniqueIndexesInPublicSchema()) {
            if (TECHNICAL_TABLES.contains(index.tableName())) {
                continue;
            }
            String requiredColumn =
                    index.tableName().equals(ROOT_TABLE) ? "id" : "institution_id";
            assertThat(index.columns())
                    .as("unique index %s on %s must include the discriminator column %s",
                            index.name(), index.tableName(), requiredColumn)
                    .contains(requiredColumn);
        }
    }

    @Test
    void twoInstitutionsAreIsolatedAndAnAbsentOrEmptyContextDeniesRatherThanErrors() {
        InstitutionId first = new InstitutionId(UUID.randomUUID());
        InstitutionId second = new InstitutionId(UUID.randomUUID());
        seedMinimalInstitution(first, "08019999000001");
        seedMinimalInstitution(second, "08019999000002");

        assertThat(countVisibleRows(first, second)).isZero();
        assertThat(countVisibleRows(first, first)).isEqualTo(1);

        dsl.execute("reset app.institution_id");
        assertThat(countAllVisibleRows()).isZero();

        dsl.execute("select set_config('app.institution_id', '', true)");
        assertThat(countAllVisibleRows()).isZero();
    }

    @Test
    void everyBusinessTableNameCarriesItsOwnerModulesPrefixExceptTheClosedCatalogue() {
        Set<String> modules = productionModuleNames();
        assertThat(modules)
                .as("the module set is derived from real production packages, so it can never be "
                        + "empty while this test runs at all")
                .contains("organization");

        for (BaseTable table : baseTablesInPublicSchema()) {
            if (TECHNICAL_TABLES.contains(table.name())) {
                continue;
            }
            int separator = table.name().indexOf('_');
            assertThat(separator)
                    .as("business table %s must carry a '<módulo>_' prefix", table.name())
                    .isPositive();
            assertThat(modules)
                    .as("business table %s must be prefixed with the name of a module that really "
                            + "exists (ADR-0017); the root table is not exempted", table.name())
                    .contains(table.name().substring(0, separator));
        }
    }

    /**
     * Business module names read from the real production packages under {@code com.confia},
     * never a hand-written list and never a shape-only regular expression. A regular expression
     * such as {@code ^[a-z]+_[a-z_]+$} accepts {@code tmp_import} or {@code legacy_data}, which is
     * precisely the kind of table this gate exists to reject: task 3.5 asks for the prefix of the
     * table's <em>owner module</em>, so the prefix is checked against the modules that actually
     * exist. The module of a class is the first package segment after {@code com.confia}, the same
     * criterion {@code NoCrossModuleDomainImportsTest} uses, and test classes are excluded so the
     * architecture fixtures never invent a module name.
     */
    private static Set<String> productionModuleNames() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.confia").stream()
                .map(JavaClass::getPackageName)
                .filter(name -> name.startsWith(BASE_PACKAGE_PREFIX))
                .map(name -> name.substring(BASE_PACKAGE_PREFIX.length()).split("\\.")[0])
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * {@code mfa_required} is fixed at account creation, with no role data backing it
     * (column-encryption-and-mfa-totp design.md decision 3, point 1; specs/identity/spec.md,
     * "La columna se fija al crear la cuenta, sin ningún dato de rol que la respalde"). Two staff
     * accounts, seeded with the column set explicitly to {@code true} and to {@code false}, round
     * trip to exactly the value each one was given — there is no {@code DEFAULT} and no derivation
     * from any permission concept, which does not exist yet in this tree.
     */
    @Test
    void mfaRequiredIsFixedAtAccountCreationWithNoRoleDataBackingIt() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        UUID requiredAccountId = UUID.randomUUID();
        UUID notRequiredAccountId = UUID.randomUUID();

        withInstitutionContext(institutionId, () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, true)
                    """, institutionId.value(), requiredAccountId, "mfa.required@colegio.edu.hn",
                    PLACEHOLDER_PASSWORD_HASH);
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, false)
                    """, institutionId.value(), notRequiredAccountId,
                    "mfa.not-required@colegio.edu.hn", PLACEHOLDER_PASSWORD_HASH);
        });

        withInstitutionContext(institutionId, () -> {
            assertThat(mfaRequiredOf(requiredAccountId)).isTrue();
            assertThat(mfaRequiredOf(notRequiredAccountId)).isFalse();
        });
    }

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";

    private boolean mfaRequiredOf(UUID accountId) {
        return dsl.fetchOne("select mfa_required from identity_staff_account where id = ?",
                accountId).get("mfa_required", Boolean.class);
    }

    @Test
    void theRtnColumnCommentDeclaresATechnicalGuardNotAFiscalRule() {
        String comment = columnCommentOf(ROOT_TABLE, "rtn");

        assertThat(comment)
                .as("organization_institution.rtn's column comment, read from the real schema "
                        + "with col_description, must declare the length limit as a technical "
                        + "guard, not a fiscal rule (specs/organization/spec.md)")
                .containsIgnoringCase("technical guard")
                .containsIgnoringCase("not a fiscal rule");
    }

    @Test
    void theDatabaseRejectsAnRtnLongerThanTheTechnicalGuardEvenBypassingTheDomain() {
        InstitutionId id = new InstitutionId(UUID.randomUUID());
        String tooLongRtn = "1".repeat(21);

        // The column's own VARCHAR(20) length limit rejects this before Postgres even evaluates
        // the organization_institution_rtn_digits CHECK constraint (real, observed behavior: the
        // varchar length guard fires first) — still the same technical guard the spec requires,
        // enforced at the schema level regardless of which mechanism reports it.
        assertThatThrownBy(() -> seedMinimalInstitution(id, tooLongRtn))
                .as("a direct SQL insert bypassing domain validation must still be rejected by "
                        + "the column's own length guard")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("value too long for type character varying(20)");
    }

    /**
     * Seeds one minimal institution row by direct SQL, with the session's {@code
     * app.institution_id} set to {@code id} itself (design.md decision 6, point 2: {@code WITH
     * CHECK} constrains the owner's own INSERT too).
     */
    private void seedMinimalInstitution(InstitutionId id, String rtn) {
        withInstitutionContext(id, () -> dsl.execute("""
                insert into organization_institution
                    (id, legal_name, rtn, address, default_currency, locale, timezone)
                values (?, 'Institución de prueba', ?, 'Dirección de prueba', 'HNL', 'es-HN',
                    'America/Tegucigalpa')
                """, id.value(), rtn));
    }

    /** Counts rows of {@code id = queryId} visible under {@code contextId}'s session context. */
    private long countVisibleRows(InstitutionId contextId, InstitutionId queryId) {
        long[] result = new long[1];
        withInstitutionContext(contextId, () -> result[0] = dsl
                .fetchOne("select count(*) as c from organization_institution where id = ?",
                        queryId.value())
                .get("c", Number.class).longValue());
        return result[0];
    }

    /** Counts every row visible under whatever session context is currently in effect. */
    private long countAllVisibleRows() {
        return dsl.fetchOne("select count(*) as c from organization_institution")
                .get("c", Number.class).longValue();
    }

    /**
     * Every base table of the {@code public} schema, with whether it declares {@code
     * institution_id} as {@code NOT NULL} — queried from {@code information_schema}, never a
     * hand-maintained list (task 3.5, point 1).
     */
    /**
     * Every base table of the {@code public} schema, read from {@code pg_class} and {@code
     * pg_attribute}, never from {@code information_schema}.
     *
     * <p>This distinction is the whole value of the gate. {@code information_schema} views are
     * filtered by the privileges of the role running the query, so a table the connecting role
     * holds no privilege on is simply invisible there. These tests connect as {@code
     * confia_admin_app}, so a new table created with no {@code institution_id}, no row-level
     * security and no {@code GRANT} would have passed every assertion in this class silently —
     * exactly the table this gate exists to reject. Verified by adding such a table and observing
     * that the gates only saw it once a {@code GRANT} was added. {@code pg_catalog} applies no
     * such filter.
     */
    private List<BaseTable> baseTablesInPublicSchema() {
        List<Record> rows = dsl.fetch("""
                select c.relname as table_name,
                       exists (
                           select 1 from pg_attribute a
                           where a.attrelid = c.oid and a.attname = 'institution_id'
                             and a.attnum > 0 and not a.attisdropped and a.attnotnull
                       ) as has_institution_id
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relkind in ('r', 'p')
                order by c.relname
                """);
        List<BaseTable> tables = new ArrayList<>();
        for (Record row : rows) {
            tables.add(new BaseTable(row.get("table_name", String.class),
                    row.get("has_institution_id", Boolean.class)));
        }
        return tables;
    }

    /** {@code pg_class.relrowsecurity} and {@code relforcerowsecurity} for one table. */
    private RowSecurityFlags rowSecurityFlagsOf(String tableName) {
        Record row = dsl.fetchOne("""
                select relrowsecurity, relforcerowsecurity
                from pg_class
                where relname = ? and relnamespace = 'public'::regnamespace
                """, tableName);
        return new RowSecurityFlags(row.get("relrowsecurity", Boolean.class),
                row.get("relforcerowsecurity", Boolean.class));
    }

    /**
     * The column comment of {@code tableName.columnName}, read from the real schema with {@code
     * col_description} (task 3.5b): proof that the RTN guard is declared on the schema itself,
     * not only in the migration file's own SQL comment, which never reaches the catalogue.
     */
    private String columnCommentOf(String tableName, String columnName) {
        return dsl.fetchOne("""
                select col_description(
                    (quote_ident(?))::regclass::oid,
                    (select attnum from pg_attribute
                     where attrelid = (quote_ident(?))::regclass and attname = ?)
                ) as comment
                """, tableName, tableName, columnName).get("comment", String.class);
    }

    /** Number of {@code pg_policies} rows declared for one table. */
    private long policyCountOn(String tableName) {
        return dsl.fetchOne("""
                select count(*) as c from pg_policies
                where schemaname = 'public' and tablename = ?
                """, tableName).get("c", Number.class).longValue();
    }

    /**
     * Every unique index of the {@code public} schema (backed by a {@code UNIQUE} constraint, a
     * standalone unique index, or a primary key — PostgreSQL backs every primary key with an
     * implicit unique index), with its participating columns, read from {@code pg_catalog}
     * directly rather than a hand-maintained list (task 3.5, point 3). Column names are fetched
     * one row per (index, column) pair and grouped in Java, rather than aggregated in SQL, to
     * avoid depending on how the PostgreSQL driver maps a {@code text[]} result for a plain-SQL
     * query with no generated binding.
     */
    private List<UniqueIndex> uniqueIndexesInPublicSchema() {
        List<Record> rows = dsl.fetch("""
                select i.relname as index_name, t.relname as table_name, a.attname as column_name
                from pg_index ix
                join pg_class i on i.oid = ix.indexrelid
                join pg_class t on t.oid = ix.indrelid
                join pg_namespace n on n.oid = t.relnamespace
                join pg_attribute a on a.attrelid = t.oid and a.attnum = any(ix.indkey)
                where ix.indisunique = true and n.nspname = 'public'
                order by i.relname, array_position(ix.indkey, a.attnum)
                """);
        Map<String, String> tableByIndex = new LinkedHashMap<>();
        Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
        for (Record row : rows) {
            String indexName = row.get("index_name", String.class);
            tableByIndex.put(indexName, row.get("table_name", String.class));
            columnsByIndex.computeIfAbsent(indexName, key -> new ArrayList<>())
                    .add(row.get("column_name", String.class));
        }
        List<UniqueIndex> indexes = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : columnsByIndex.entrySet()) {
            indexes.add(new UniqueIndex(entry.getKey(), tableByIndex.get(entry.getKey()),
                    entry.getValue()));
        }
        return indexes;
    }

    private record BaseTable(String name, boolean hasNotNullInstitutionId) {
    }

    private record RowSecurityFlags(boolean rowSecurity, boolean forceRowSecurity) {
    }

    private record UniqueIndex(String name, String tableName, List<String> columns) {
    }
}
