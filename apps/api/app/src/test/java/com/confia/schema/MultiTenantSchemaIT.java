package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.support.TransactionalPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/**
 * Schema catalogue gates over the real PostgreSQL schema created in PR A2 (design.md decision 10,
 * points 1 to 4; specs/build-integrity/spec.md). Every assertion here queries {@code pg_catalog}
 * and {@code information_schema} directly, never a hand-maintained list of table names, so a new
 * table added later without the required invariant breaks this build without anyone having to
 * remember to extend this class.
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

    private static final Pattern MODULE_PREFIXED_TABLE_NAME =
            Pattern.compile("^[a-z][a-z0-9]*_[a-z0-9_]+$");

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
        for (BaseTable table : baseTablesInPublicSchema()) {
            if (TECHNICAL_TABLES.contains(table.name())) {
                continue;
            }
            assertThat(MODULE_PREFIXED_TABLE_NAME.matcher(table.name()).matches())
                    .as("business table %s must carry its owner module's '<módulo>_' prefix",
                            table.name())
                    .isTrue();
        }
        assertThat(ROOT_TABLE).as("the root table is not exempted from the module prefix")
                .startsWith("organization_");
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
    private List<BaseTable> baseTablesInPublicSchema() {
        List<Record> rows = dsl.fetch("""
                select t.table_name as table_name,
                       exists (
                           select 1 from information_schema.columns c
                           where c.table_schema = 'public' and c.table_name = t.table_name
                             and c.column_name = 'institution_id' and c.is_nullable = 'NO'
                       ) as has_institution_id
                from information_schema.tables t
                where t.table_schema = 'public' and t.table_type = 'BASE TABLE'
                order by t.table_name
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
