package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * docs/03-seguridad.md's {@code no-console-in-api} Semgrep rule, expressed as an ArchUnit rule
 * (pre-merge review of PR #1, finding 1): no production class may write to {@code System.out} or
 * {@code System.err}, nor call {@code Throwable.printStackTrace()}. Both bypass the redaction the
 * structured JSON logger applies, which is exactly what let {@code ConfiaApplication} log a raw
 * {@code System.err.println} before this rule existed.
 *
 * <p>Backed by ArchUnit's own {@link
 * com.tngtech.archunit.library.GeneralCodingRules#NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS}, kept
 * here rather than reimplemented so this rule stays in step with any future ArchUnit refinement of
 * what counts as "standard stream access".
 */
class NoStandardStreamAccessTest {

    private static final ArchRule RULE = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @Test
    void productionCodeNeverAccessesStandardStreamsYet() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureStandardStreamUsage() {
        assertRuleRejects(RULE, fixtureClasses(), "BadStandardStreamUsage");
    }
}
