package com.confia.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Shared import scopes for every architecture rule in this package (design.md decision 3).
 *
 * <p>{@link #productionClasses()} uses ArchUnit's own {@link
 * ImportOption.Predefined#DO_NOT_INCLUDE_TESTS}, which excludes anything compiled to
 * {@code target/test-classes}. That automatically excludes {@code com.confia.architecture} and
 * {@code com.confia.architecture.fixture} themselves, with no separate exclude list to keep in
 * sync: "las reglas de producción excluyen el paquete de fixtures" holds for the whole test source
 * tree, not just the fixture package by name.
 */
final class ArchitectureTestSupport {

    static final String BASE_PACKAGE = "com.confia";
    static final String FIXTURE_PACKAGE = "com.confia.architecture.fixture";

    private ArchitectureTestSupport() {
    }

    /** Everything under {@value #BASE_PACKAGE} that is production code, today or in the future. */
    static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE_PACKAGE);
    }

    /** Only the permanent, deliberately invalid fixture package (task 3.3). */
    static JavaClasses fixtureClasses() {
        return new ClassFileImporter().importPackages(FIXTURE_PACKAGE);
    }

    /**
     * Asserts that {@code rule} rejects {@code classes}. Every architecture rule in this package
     * uses this against {@link #fixtureClasses()} as the second half of its two-test proof
     * (design.md decision 3, task 3.4): a rule that never failed protects nothing.
     *
     * <p>With {@code archRule.failOnEmptyShould=true} restored (ADR-0018), ArchUnit itself throws
     * an {@link AssertionError} when a rule's scope matches zero classes — the exact same
     * exception type a real violation throws. Checking only {@code isInstanceOf(AssertionError)}
     * would let a fixture that had stopped matching its rule (for example after someone deleted
     * {@link #FIXTURE_PACKAGE}) read as "the rule correctly rejected it", which is precisely the
     * false confidence ADR-0018's compliance section 4 forbids. The two extra assertions below
     * rule out ArchUnit's own empty-set wording ("failed to check any classes", "Layer 'X' is
     * empty") so only a genuine violation counts as a rejection.
     */
    static void assertRuleRejects(ArchRule rule, JavaClasses classes) {
        assertThatThrownBy(() -> rule.check(classes))
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("failed to check any classes")
                .hasMessageNotContaining("is empty");
    }
}
