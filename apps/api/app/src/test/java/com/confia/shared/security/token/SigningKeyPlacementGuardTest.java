package com.confia.shared.security.token;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The placement check of ADR-0005, check 14, on its own (design.md, decision 3, table of the
 * startup check): which names each kind of process refuses, and that the refusal reads them the
 * way Spring Boot binds names, so that an operating-system variable is found like an argument.
 * The processes themselves are started in {@code SigningKeyStartupTest}.
 */
class SigningKeyPlacementGuardTest {

    private static final String ADMIN_CURRENT_PRIVATE =
            "confia.security.admin-signing.current.private-key";
    private static final String ADMIN_PREVIOUS_PRIVATE =
            "confia.security.admin-signing.previous.private-key";
    private static final String ADMIN_CURRENT_PUBLIC =
            "confia.security.admin-signing.current.public-key";
    private static final String PORTAL_CURRENT_PRIVATE =
            "confia.security.portal-signing.current.private-key";
    private static final String PORTAL_PREVIOUS_PRIVATE =
            "confia.security.portal-signing.previous.private-key";

    private static final String VALUE = "Qx7!Rk2#Jv9%Wm5@";

    @ParameterizedTest
    @CsvSource({
            "CONFIA_SECURITY_ADMINSIGNING_CURRENT_PRIVATEKEY," + ADMIN_CURRENT_PRIVATE,
            "CONFIA_SECURITY_ADMINSIGNING_PREVIOUS_PRIVATEKEY," + ADMIN_PREVIOUS_PRIVATE})
    void theNonAdministrativeGuardFindsTheAdministrativePrivateKeyInAnOperatingSystemVariable(
            String variable, String property) {
        StandardEnvironment environment = environmentWithVariable(variable);

        assertThatThrownBy(() -> SigningKeyPlacementGuard.forNonAdministrativeProcess()
                .onApplicationEvent(eventOf(environment)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(property)
                .hasMessageNotContaining(VALUE)
                .hasNoCause();
    }

    @ParameterizedTest
    @CsvSource({
            "CONFIA_SECURITY_PORTALSIGNING_CURRENT_PRIVATEKEY," + PORTAL_CURRENT_PRIVATE,
            "CONFIA_SECURITY_PORTALSIGNING_PREVIOUS_PRIVATEKEY," + PORTAL_PREVIOUS_PRIVATE})
    void theAdministrativeGuardFindsTheReservedPortalNameInAnOperatingSystemVariable(
            String variable, String property) {
        StandardEnvironment environment = environmentWithVariable(variable);

        assertThatThrownBy(() -> SigningKeyPlacementGuard.forAdministrativeProcess()
                .onApplicationEvent(eventOf(environment)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(property)
                .hasMessageNotContaining(VALUE)
                .hasNoCause();
    }

    @Test
    void theNonAdministrativeGuardNamesOnlyThePropertyThatIsPresent() {
        StandardEnvironment environment = environmentWith(Map.of(ADMIN_CURRENT_PRIVATE, VALUE));

        assertThatThrownBy(() -> SigningKeyPlacementGuard.forNonAdministrativeProcess()
                .onApplicationEvent(eventOf(environment)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ADMIN_CURRENT_PRIVATE)
                .hasMessageNotContaining(ADMIN_PREVIOUS_PRIVATE);
    }

    @Test
    void thePlacementOfThePublicKeysIsNotRestricted() {
        StandardEnvironment environment = environmentWith(Map.of(ADMIN_CURRENT_PUBLIC, VALUE));

        assertThatCode(() -> SigningKeyPlacementGuard.forNonAdministrativeProcess()
                .onApplicationEvent(eventOf(environment))).doesNotThrowAnyException();
        assertThatCode(() -> SigningKeyPlacementGuard.forAdministrativeProcess()
                .onApplicationEvent(eventOf(environment))).doesNotThrowAnyException();
    }

    @Test
    void theAdministrativeGuardDoesNotRefuseItsOwnPrivateKeysAndTheOthersDoNotRefuseThePortalNames() {
        StandardEnvironment administrative = environmentWith(
                Map.of(ADMIN_CURRENT_PRIVATE, VALUE, ADMIN_PREVIOUS_PRIVATE, VALUE));
        StandardEnvironment portal = environmentWith(
                Map.of(PORTAL_CURRENT_PRIVATE, VALUE, PORTAL_PREVIOUS_PRIVATE, VALUE));

        assertThatCode(() -> SigningKeyPlacementGuard.forAdministrativeProcess()
                .onApplicationEvent(eventOf(administrative))).doesNotThrowAnyException();
        assertThatCode(() -> SigningKeyPlacementGuard.forNonAdministrativeProcess()
                .onApplicationEvent(eventOf(portal))).doesNotThrowAnyException();
    }

    @Test
    void anEmptyEnvironmentIsAccepted() {
        assertThatCode(() -> SigningKeyPlacementGuard.forAdministrativeProcess()
                .onApplicationEvent(eventOf(new StandardEnvironment()))).doesNotThrowAnyException();
        assertThatCode(() -> SigningKeyPlacementGuard.forNonAdministrativeProcess()
                .onApplicationEvent(eventOf(new StandardEnvironment()))).doesNotThrowAnyException();
    }

    private static StandardEnvironment environmentWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("guard-test", properties));
        ConfigurationPropertySources.attach(environment);
        return environment;
    }

    private static StandardEnvironment environmentWithVariable(String variable) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "guard-test-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of(variable, VALUE)));
        ConfigurationPropertySources.attach(environment);
        return environment;
    }

    private static ApplicationEnvironmentPreparedEvent eventOf(StandardEnvironment environment) {
        return new ApplicationEnvironmentPreparedEvent(new DefaultBootstrapContext(),
                new SpringApplication(), new String[0], environment);
    }
}
