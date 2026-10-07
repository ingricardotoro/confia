package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rule BI11-BI13 and BI38 (session-tokens-and-web-layer design.md, decision 16; specs/build-integrity,
 * requirement "Ninguna utilidad de firma ni de hash fuera de identity y shared.security"): no
 * production class outside {@code com.confia.identity} and {@code com.confia.shared.security}
 * (subpackages included, such as {@code shared.security.token}) depends on a signing or hashing
 * utility: {@code MessageDigest}, {@code Signature}, {@code KeyFactory}, {@code KeyPairGenerator},
 * {@code Mac}, {@code KeyGenerator} or any {@code org.bouncycastle} class. Anything that must hash
 * outside those packages goes through {@code com.confia.shared.security.Digests}.
 *
 * <p>The rule has no allow-list, no exception inventory and no per-class exemption: the one
 * pre-existing use outside the two packages, {@code CanonicalAuditRowSerializer}, was moved to
 * {@code Digests}. {@code javax.crypto.Cipher} and {@code SecureRandom} are deliberately out of
 * scope: encryption and randomness are not signing or hashing, and {@code GoodCipherOutside} and
 * {@code GoodSecureRandomOutside} prove the rule leaves them alone.
 *
 * <p>Both halves of the repository's two-half convention (ADR-0018). The production half has no
 * empty-set exception, and the non-vacuity tests prove it looked at real classes of {@code
 * identity}, of {@code shared.security} and of the rest. The fixture half must reject each of the
 * seven forbidden utilities, naming the class and the utility. The rule evaluates production
 * classes only ({@code DO_NOT_INCLUDE_TESTS}), so a test-tree helper such as {@code JwsFixtures}
 * never matters.
 */
class SigningAndHashingConfinementTest {

    private static final String IDENTITY_PACKAGE = "com.confia.identity..";
    private static final String SHARED_SECURITY_PACKAGE = "com.confia.shared.security..";
    private static final String FIXTURE_PREFIX = "com.confia.architecture.fixture.hashing.outside.";

    private static final List<String> FORBIDDEN_TYPES = List.of(
            "java.security.MessageDigest",
            "java.security.Signature",
            "java.security.KeyFactory",
            "java.security.KeyPairGenerator",
            "javax.crypto.Mac",
            "javax.crypto.KeyGenerator");
    private static final String BOUNCY_CASTLE_PREFIX = "org.bouncycastle.";

    private static final DescribedPredicate<JavaClass> FORBIDDEN_UTILITY =
            new DescribedPredicate<>("a signing or hashing utility (MessageDigest, Signature, "
                    + "KeyFactory, KeyPairGenerator, Mac, KeyGenerator or org.bouncycastle..)") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return isForbiddenUtility(javaClass);
                }
            };

    private static final ArchRule RULE = noClasses()
            .that().resideOutsideOfPackages(IDENTITY_PACKAGE, SHARED_SECURITY_PACKAGE)
            .should().dependOnClassesThat(FORBIDDEN_UTILITY)
            .because("signing and hashing live in identity and shared.security only; anything else "
                    + "uses com.confia.shared.security.Digests (session-tokens-and-web-layer "
                    + "design.md, decision 16)");

    @Test
    void productionCodeOutsideIdentityAndSharedSecurityNeitherSignsNorHashes() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureOfEachOfTheSevenForbiddenUtilities() {
        assertRuleRejects(RULE, fixtureClasses(),
                "BadDigestOutsideIdentity", "java.security.MessageDigest",
                "BadSignatureOutside", "java.security.Signature",
                "BadKeyFactoryOutside", "java.security.KeyFactory",
                "BadKeyPairGeneratorOutside", "java.security.KeyPairGenerator",
                "BadMacOutside", "javax.crypto.Mac",
                "BadKeyGeneratorOutside", "javax.crypto.KeyGenerator",
                "BadBouncyCastleOutside", "org.bouncycastle");
    }

    @Test
    void doesNotRejectTheFixturesThatOnlyEncryptOrGenerateRandomBytes() {
        assertThatThrownBy(() -> RULE.check(fixtureClasses()))
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("GoodCipherOutside")
                .hasMessageNotContaining("GoodSecureRandomOutside");
        assertThat(fixtureClasses().stream().map(JavaClass::getName))
                .as("the two out-of-scope fixtures exist and are evaluated")
                .contains(FIXTURE_PREFIX + "GoodCipherOutside",
                        FIXTURE_PREFIX + "GoodSecureRandomOutside");
    }

    /** Non-vacuity of the allowed side: real classes of both packages use the utilities. */
    @Test
    void identityAndSharedSecurityReallyUseTheForbiddenUtilities() {
        assertThat(usersInside("com.confia.identity."))
                .as("production classes of identity that use a signing or hashing utility")
                .contains("com.confia.identity.domain.PasswordResetTokenHash",
                        "com.confia.identity.domain.TotpAlgorithm");
        assertThat(usersInside("com.confia.shared.security."))
                .as("production classes of shared.security that use a signing or hashing utility")
                .contains("com.confia.shared.security.Digests",
                        "com.confia.shared.security.token.Ed25519Signatures");
    }

    /** Non-vacuity of the scope: the rule evaluates real classes of the rest of the packages. */
    @Test
    void theRuleEvaluatesRealProductionClassesOutsideTheTwoPackages() {
        List<String> outside = productionClasses().stream().map(JavaClass::getName)
                .filter(name -> !name.startsWith("com.confia.identity.")
                        && !name.startsWith("com.confia.shared.security."))
                .toList();

        assertThat(outside).contains("com.confia.shared.audit.CanonicalAuditRowSerializer",
                "com.confia.shared.web.problem.ProblemBody");
    }

    /**
     * The audit chain no longer hashes by itself: it depends on {@code Digests} and not on {@code
     * MessageDigest} (BI13), so its bytes are the ones the existing chain tests already pin.
     */
    @Test
    void theAuditChainSerializerDelegatesItsHashToTheSecurityUtility() {
        JavaClass serializer =
                productionClasses().get("com.confia.shared.audit.CanonicalAuditRowSerializer");

        assertThat(serializer.getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getName()))
                .contains("com.confia.shared.security.Digests")
                .doesNotContain("java.security.MessageDigest");
    }

    private static List<String> usersInside(String packagePrefix) {
        return productionClasses().stream()
                .filter(javaClass -> javaClass.getName().startsWith(packagePrefix))
                .filter(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency -> isForbiddenUtility(dependency.getTargetClass())))
                .map(JavaClass::getName)
                .toList();
    }

    private static boolean isForbiddenUtility(JavaClass javaClass) {
        String name = javaClass.getName();
        return FORBIDDEN_TYPES.contains(name) || name.startsWith(BOUNCY_CASTLE_PREFIX);
    }
}
