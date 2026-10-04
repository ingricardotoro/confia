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

    @Test
    void fromValueResolvesTheConfiguredInstitutionId() {
        UUID institutionId = UUID.randomUUID();

        ConfiguredLoginInstitutionProvider provider =
                ConfiguredLoginInstitutionProvider.fromValue(institutionId.toString());

        assertThat(provider.loginInstitutionId()).isEqualTo(new InstitutionId(institutionId));
    }

    @Test
    void fromValueToleratesSurroundingWhitespace() {
        UUID institutionId = UUID.randomUUID();

        ConfiguredLoginInstitutionProvider provider =
                ConfiguredLoginInstitutionProvider.fromValue("\t" + institutionId + " \n");

        assertThat(provider.loginInstitutionId()).isEqualTo(new InstitutionId(institutionId));
    }

    @Test
    void fromValueFailsWhenTheValueIsAbsentOrBlankNamingTheKey() {
        assertThatThrownBy(() -> ConfiguredLoginInstitutionProvider.fromValue(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ConfiguredLoginInstitutionProvider.CONFIG_KEY);
        assertThatThrownBy(() -> ConfiguredLoginInstitutionProvider.fromValue("  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ConfiguredLoginInstitutionProvider.CONFIG_KEY);
    }

    /**
     * A malformed value is never repeated and the parser's exception is never chained: its message
     * would repeat the value (CLAUDE.md, regla 11).
     */
    @Test
    void fromValueRejectsAMalformedValueWithoutEchoingItOrChainingTheParser() {
        String malformed = "Qx7!Rk2#Jv9%Wm5@";

        assertThatThrownBy(() -> ConfiguredLoginInstitutionProvider.fromValue(malformed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ConfiguredLoginInstitutionProvider.CONFIG_KEY)
                .hasMessageNotContaining("Qx7!")
                .hasMessageNotContaining("Rk2#")
                .hasMessageNotContaining("Wm5@")
                .hasNoCause();
    }
}
