package com.confia.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.Institution;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ResolveCurrentInstitution} (specs/organization/spec.md, requirement "Caso de uso de
 * resolución de la institución en curso") and its two output port test doubles
 * ({@link InMemoryInstitutionRepository}, {@link FixedCurrentInstitutionProvider}; design.md,
 * decision 8, "Dobles en memoria" — the real adapters land in changes 5 and 7).
 */
class ResolveCurrentInstitutionTest {

    @Test
    void inMemoryRepositoryReturnsARegisteredInstitutionForItsIdentifier() {
        InstitutionId id = anId();
        Institution institution = anActiveInstitution(id);
        InMemoryInstitutionRepository repository = new InMemoryInstitutionRepository();

        repository.register(institution);

        assertThat(repository.findById(id)).contains(institution);
    }

    @Test
    void fixedProviderReturnsItsConfiguredIdentifier() {
        InstitutionId id = anId();
        FixedCurrentInstitutionProvider provider = new FixedCurrentInstitutionProvider(id);

        assertThat(provider.currentInstitutionId()).isEqualTo(id);
    }

    @Test
    void resolvesTheActiveInstitutionForTheCurrentRequest() {
        InstitutionId id = anId();
        Institution activeInstitution = anActiveInstitution(id);
        InMemoryInstitutionRepository repository = new InMemoryInstitutionRepository();
        repository.register(activeInstitution);
        ResolveCurrentInstitution useCase = new ResolveCurrentInstitution(
                new FixedCurrentInstitutionProvider(id), repository);

        Institution resolved = useCase.execute();

        assertThat(resolved).isEqualTo(activeInstitution);
    }

    @Test
    void constructorRejectsANullCurrentInstitutionProvider() {
        InstitutionRepository repository = new InMemoryInstitutionRepository();

        assertThatNullPointerException()
                .isThrownBy(() -> new ResolveCurrentInstitution(null, repository));
    }

    @Test
    void constructorRejectsANullInstitutionRepository() {
        CurrentInstitutionProvider provider = new FixedCurrentInstitutionProvider(anId());

        assertThatNullPointerException()
                .isThrownBy(() -> new ResolveCurrentInstitution(provider, null));
    }

    private static InstitutionId anId() {
        return new InstitutionId(UUID.randomUUID());
    }

    private static Institution anActiveInstitution(InstitutionId id) {
        return Institution.create(id, "Instituto San Marcos", "Colegio San Marcos",
                "08019012345678", "Colonia Palmira, Tegucigalpa", CurrencyCode.HNL,
                Locale.forLanguageTag("es-HN"), ZoneId.of("America/Tegucigalpa"));
    }
}
