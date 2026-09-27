package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ConfiguredLoginInstitutionProvider} fails at construction, never on first use (design.md,
 * §6.1, decision 11). No Docker: this class touches no PostgreSQL at all.
 */
class ConfiguredLoginInstitutionProviderTest {

    @Test
    void resolvesTheConfiguredInstitutionId() {
        UUID institutionId = UUID.randomUUID();

        ConfiguredLoginInstitutionProvider provider =
                new ConfiguredLoginInstitutionProvider(institutionId.toString());

        assertThat(provider.loginInstitutionId()).isEqualTo(new InstitutionId(institutionId));
    }

    @Test
    void toleratesSurroundingWhitespace() {
        UUID institutionId = UUID.randomUUID();

        ConfiguredLoginInstitutionProvider provider =
                new ConfiguredLoginInstitutionProvider("  " + institutionId + "  ");

        assertThat(provider.loginInstitutionId()).isEqualTo(new InstitutionId(institutionId));
    }

    @Test
    void failsAtConstructionWhenTheValueIsAbsent() {
        assertThatThrownBy(() -> new ConfiguredLoginInstitutionProvider((String) null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ConfiguredLoginInstitutionProvider.CONFIG_KEY);
    }

    @Test
    void failsAtConstructionWhenTheValueIsBlank() {
        assertThatThrownBy(() -> new ConfiguredLoginInstitutionProvider("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ConfiguredLoginInstitutionProvider.CONFIG_KEY);
    }

    @Test
    void failsAtConstructionWhenTheValueIsNotAValidUuid() {
        assertThatThrownBy(() -> new ConfiguredLoginInstitutionProvider("not-a-uuid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ConfiguredLoginInstitutionProvider.CONFIG_KEY);
    }

    @Test
    void readsFromTheSystemPropertyThroughTheNoArgConstructor() {
        UUID institutionId = UUID.randomUUID();
        System.setProperty(ConfiguredLoginInstitutionProvider.CONFIG_KEY, institutionId.toString());
        try {
            ConfiguredLoginInstitutionProvider provider = new ConfiguredLoginInstitutionProvider();
            assertThat(provider.loginInstitutionId()).isEqualTo(new InstitutionId(institutionId));
        } finally {
            System.clearProperty(ConfiguredLoginInstitutionProvider.CONFIG_KEY);
        }
    }
}
