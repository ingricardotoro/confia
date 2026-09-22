package com.confia.organization.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.Institution;
import com.confia.support.TransactionalPostgresIntegrationTest;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

/**
 * Observable contract of the read-only jOOQ {@link JooqInstitutionRepository} against a real
 * {@code organization_institution} table (specs/organization/spec.md, requirement "Contrato
 * observable del adaptador jOOQ de InstitutionRepository contra la base real"; design.md decision
 * 8). Every row this class needs is seeded by direct SQL, never through the adapter itself: the
 * port only declares {@code findById}, and a write method with no production consumer would be
 * exactly the kind of speculative surface ADR-0019 exists to prevent (specs/organization/spec.md,
 * same requirement, "la siembra de filas en las pruebas es responsabilidad explícita de la
 * prueba").
 *
 * <p>Seeding goes through {@link #withInstitutionContext} because {@code
 * organization_institution} has {@code FORCE ROW LEVEL SECURITY} with an explicit {@code WITH
 * CHECK} (design.md decision 6, point 2): inserting a row requires the session's {@code
 * app.institution_id} to already equal the id being inserted, even for the table owner running
 * the migration that created the policy.
 *
 * <p>Extends {@link TransactionalPostgresIntegrationTest}, not the bare {@link
 * com.confia.support.PostgresIntegrationTest}, so every seeded row rolls back at the end of each
 * test method and one test can never see another test's data on the single container shared per
 * JVM (apply-progress.md, "Discrepancia reportada... variantes de PostgresIntegrationTest").
 */
class JooqInstitutionRepositoryIT extends TransactionalPostgresIntegrationTest {

    private JooqInstitutionRepository repository;

    private JooqInstitutionRepository repository() {
        if (repository == null) {
            repository = new JooqInstitutionRepository(dsl);
        }
        return repository;
    }

    @Test
    void reconstructsEveryAttributeFromTheRealSchema() {
        InstitutionId id = new InstitutionId(UUID.randomUUID());
        seedRow(id, "Colegio Real S.A.", "Colegio Real", "08011999123456",
                "Boulevard Morazán, Tegucigalpa", "HNL", "es-HN", "America/Tegucigalpa");

        Optional<Institution> found = findByIdWithContext(id, id);

        assertThat(found).isPresent();
        Institution institution = found.orElseThrow();
        assertThat(institution.id()).isEqualTo(id);
        assertThat(institution.legalName()).isEqualTo("Colegio Real S.A.");
        assertThat(institution.tradeName()).isEqualTo("Colegio Real");
        // The RTN is reconstructed as the exact stored digit sequence, with no normalization
        // (specs/organization/spec.md, same scenario).
        assertThat(institution.rtn()).isEqualTo("08011999123456");
        assertThat(institution.address()).isEqualTo("Boulevard Morazán, Tegucigalpa");
        assertThat(institution.defaultCurrency()).isEqualTo(CurrencyCode.HNL);
        assertThat(institution.locale()).isEqualTo(Locale.forLanguageTag("es-HN"));
        assertThat(institution.timezone()).isEqualTo(ZoneId.of("America/Tegucigalpa"));
        assertThat(institution.isActive()).isTrue();
    }

    @Test
    void reconstructsAnAbsentTradeNameAsNullNeverAsAnEmptyString() {
        InstitutionId id = new InstitutionId(UUID.randomUUID());
        seedRow(id, "Instituto Central", null, "08011999654321",
                "Avenida La Paz, San Pedro Sula", "USD", "es-HN", "America/Tegucigalpa");

        Optional<Institution> found = findByIdWithContext(id, id);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().tradeName()).isNull();
    }

    @Test
    void returnsAnEmptyOptionalForAnUnknownIdentifierWithoutThrowing() {
        InstitutionId unknownId = new InstitutionId(UUID.randomUUID());

        Optional<Institution> found = findByIdWithContext(unknownId, unknownId);

        assertThat(found).isEmpty();
    }

    @Test
    void rejectsASecondRowWithTheSameIdentifier() {
        InstitutionId id = new InstitutionId(UUID.randomUUID());
        seedRow(id, "Primera Fila", null, "08011999111111", "Dirección uno", "HNL", "es-HN",
                "America/Tegucigalpa");

        assertThatThrownBy(() -> seedRow(id, "Segunda Fila", null, "08011999222222",
                "Dirección dos", "USD", "es-HN", "America/Tegucigalpa"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("organization_institution_pkey");
    }

    // Row-level-isolation scenarios (specs/organization/spec.md, requirement "Aislamiento por fila
    // de la tabla raíz según ADR-0009"; design.md decision 6, point 1). Green as soon as they run,
    // because the NULLIF(..., '') policy already ships with the migration created in this same PR
    // (task 2.2) — there is no separate schema change left to drive red here.

    @Test
    void aSessionCannotReadAnotherInstitutionsRow() {
        InstitutionId first = new InstitutionId(UUID.randomUUID());
        InstitutionId second = new InstitutionId(UUID.randomUUID());
        seedRow(first, "Primera Institución", null, "08011999333001", "Dirección uno", "HNL",
                "es-HN", "America/Tegucigalpa");
        seedRow(second, "Segunda Institución", null, "08011999333002", "Dirección dos", "USD",
                "es-HN", "America/Tegucigalpa");

        Optional<Institution> found = findByIdWithContext(first, second);

        assertThat(found).isEmpty();
    }

    @Test
    void aSessionCanReadItsOwnRowEvenWhileAnotherInstitutionExists() {
        InstitutionId own = new InstitutionId(UUID.randomUUID());
        InstitutionId other = new InstitutionId(UUID.randomUUID());
        seedRow(own, "Institución Propia", null, "08011999333003", "Dirección propia", "HNL",
                "es-HN", "America/Tegucigalpa");
        seedRow(other, "Otra Institución", null, "08011999333004", "Otra dirección", "USD",
                "es-HN", "America/Tegucigalpa");

        Optional<Institution> found = findByIdWithContext(own, own);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().id()).isEqualTo(own);
    }

    @Test
    void aSessionWithNoContextAtAllIsDeniedRatherThanErroring() {
        InstitutionId id = new InstitutionId(UUID.randomUUID());
        seedRow(id, "Institución Sin Contexto", null, "08011999333005", "Dirección", "HNL",
                "es-HN", "America/Tegucigalpa");
        resetInstitutionContext();

        Optional<Institution> found = repository().findById(id);

        assertThat(found).isEmpty();
    }

    @Test
    void aSessionWithAnEmptyContextIsDeniedRatherThanErroring() {
        InstitutionId id = new InstitutionId(UUID.randomUUID());
        seedRow(id, "Institución Contexto Vacío", null, "08011999333006", "Dirección", "HNL",
                "es-HN", "America/Tegucigalpa");

        dsl.execute("select set_config('app.institution_id', ?, true)", "");
        Optional<Institution> found = repository().findById(id);

        assertThat(found).isEmpty();
    }

    /**
     * Seeds one row by direct SQL, with the session's {@code app.institution_id} set to {@code id}
     * itself so the {@code WITH CHECK} half of the row-level-security policy allows the insert
     * (design.md decision 6, point 2). Bound parameters throughout, never string interpolation
     * (docs/03-seguridad.md section 6.2; CLAUDE.md rule 12).
     */
    private void seedRow(InstitutionId id, String legalName, String tradeName, String rtn,
            String address, String currency, String locale, String timezone) {
        withInstitutionContext(id, () -> dsl.execute("""
                insert into organization_institution
                    (id, legal_name, trade_name, rtn, address, default_currency, locale, timezone)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id.value(), legalName, tradeName, rtn, address, currency, locale, timezone));
    }

    /**
     * Queries {@link #repository()} with the session's {@code app.institution_id} set to {@code
     * contextId} (design.md, "Contratos e interfaces": the adapter itself never sets any session
     * context — that is this test harness's job, standing in for the part B transactional
     * component). {@link com.confia.support.PostgresIntegrationTest#withInstitutionContext} takes
     * a {@link Runnable}, so the result is captured through an {@link AtomicReference} rather than
     * changing that shared A1 helper's signature for this one caller.
     */
    private Optional<Institution> findByIdWithContext(InstitutionId contextId,
            InstitutionId queryId) {
        AtomicReference<Optional<Institution>> result = new AtomicReference<>();
        withInstitutionContext(contextId, () -> result.set(repository().findById(queryId)));
        return result.get();
    }

    /**
     * Clears {@code app.institution_id} for the rest of the current transaction. {@code
     * set_config(..., true)} is transaction-local, not statement-local (docs/03-seguridad.md
     * section 6.2), so once {@link #seedRow} sets it to seed a row, it stays set for every later
     * statement in the same {@link TransactionalPostgresIntegrationTest @Transactional} test
     * method unless explicitly cleared — exactly what this method does, so a test method can seed
     * a row under one context and then observe a genuinely absent context afterward, in the same
     * transaction, without needing a second connection.
     */
    private void resetInstitutionContext() {
        dsl.execute("reset app.institution_id");
    }
}
