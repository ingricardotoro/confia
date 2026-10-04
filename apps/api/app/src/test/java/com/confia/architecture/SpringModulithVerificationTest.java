package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.BASE_PACKAGE;
import static com.confia.architecture.ArchitectureTestSupport.FIXTURE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/**
 * ADR-0002 / ADR-0013 module-boundary verification via Spring Modulith 2 (design.md decision 3,
 * "por confirmar" #2). Confirmed API: {@code ApplicationModules.of(String, ImportOption)} treats
 * every direct sub-package of the given root as a module, and {@code verify()} throws {@link
 * Violations} (a {@link RuntimeException}) if a module accesses another module's non-API internal
 * package. {@code ApplicationModules.of(String)} defaults to {@code
 * ImportOption.Predefined.DO_NOT_INCLUDE_TESTS} internally, confirmed by decompiling
 * {@code spring-modulith-core:2.1.1}; passing an explicit {@link ImportOption} replaces it.
 *
 * <p>The production check scans {@value ArchitectureTestSupport#BASE_PACKAGE} with that same
 * {@code DO_NOT_INCLUDE_TESTS} default made explicit, which excludes the fixture package the same
 * way {@link ArchitectureTestSupport#productionClasses()} does. The fixture check instead scans
 * {@link ArchitectureTestSupport#FIXTURE_PACKAGE} with {@code ONLY_INCLUDE_TESTS} (the fixture
 * compiles to {@code target/test-classes}): with that narrower root, {@code moduleone} and
 * {@code moduletwo} each become their own Spring Modulith module, so {@code moduleone.domain}
 * reading {@code moduletwo.domain}'s internal package independently confirms the same violation
 * {@link NoCrossModuleDomainImportsTest} already proves with ArchUnit. The entry point fixture
 * ({@code entrypoints} and {@code entrypointclient}, ADR-0024) adds one more violation to the
 * same scan: {@code entrypointclient} reads an internal package of {@code entrypoints}. The
 * expected result does not change, and {@link BootstrapEntryPointRulesTest} proves that violation
 * on its own.
 */
class SpringModulithVerificationTest {

    @Test
    void productionModulesHaveNoViolationYet() {
        assertThatCode(() -> ApplicationModules
                        .of(BASE_PACKAGE, ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .verify())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsTheFixtureModuleCrossingInternalAccess() {
        ApplicationModules fixtureModules = ApplicationModules
                .of(FIXTURE_PACKAGE, ImportOption.Predefined.ONLY_INCLUDE_TESTS);

        assertThatThrownBy(fixtureModules::verify).isInstanceOf(Violations.class);
    }
}
