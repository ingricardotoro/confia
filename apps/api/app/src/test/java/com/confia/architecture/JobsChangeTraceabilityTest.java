package com.confia.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Traceability of every scenario of the four delta specs of background-jobs-with-db-scheduler to a test
 * (task 4.3; the checkable alternative of the two in {@code tasks.md}, chosen over a checklist because a
 * checklist cannot fail). The deltas are read from {@code openspec/changes/}; the day the change is archived
 * they are no longer there, and the checks that need them are skipped with that reason.
 *
 * <p>A scenario with an identifier is traced when a real test names it: its identifier, alone or inside a
 * range such as {@code BI70 to BI82}, appears in a class that declares {@code @Test}, {@code @ParameterizedTest}
 * or {@code @Property} methods, and only in one of two places: a {@code @DisplayName}, or the Javadoc of that
 * class or of one of those methods, after the word "scenario" or "spec" in the same sentence ("Scenarios BI65
 * and BI67", "Specs BJ51 and BI112"). The cue is how a test says it proves a scenario; a bare mention ("see
 * BJ37", "after BJ12") is an aside, so a class about something else that names a scenario in passing does not
 * trace it. A mention in a comment, in a helper method, in a harness, a policy or any class without test
 * methods does not count on its own. One whose test does not name it
 * is listed in {@link #UNTAGGED} with the test that covers it. One without an identifier is listed in
 * {@link #BY_TITLE} the same way, or in {@link #WITHOUT_A_TEST} with the reason no test covers it. Every
 * entry must point at a class and a method that exist, and an entry that stopped being needed fails.
 */
class JobsChangeTraceabilityTest {

    private static final String SPECS = "openspec/changes/background-jobs-with-db-scheduler/specs";
    private static final Pattern TAG = Pattern.compile("\\b(BJ|AT|BI|I)(\\d{2,3})\\b");
    private static final Pattern RANGE =
            Pattern.compile("\\b(BJ|AT|BI|I)(\\d{2,3})\\s*(?:to|a|through|-)\\s*(?:BJ|AT|BI|I)?(\\d{2,3})\\b");
    private static final Pattern SCENARIO = Pattern.compile("^#### Escenario: (?:\\[([A-Z]+\\d+)\\] )?(.+)$");

    private static final Pattern TEST_METHOD = Pattern.compile("(?m)^\\s*@(?:Test|ParameterizedTest|Property)\\b");
    private static final Pattern DISPLAY_NAME = Pattern.compile("@DisplayName\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern JAVADOC = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL);
    /** The words with which a Javadoc says what it proves: scenario(s), escenario(s), spec(s). */
    private static final Pattern CUE = Pattern.compile("(?i)\\b(?:scenarios?|escenarios?|specs?)\\b");
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "\\A\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:(?:public|protected|private|static|final|abstract|sealed)\\s+)*(?:class|interface|record|enum)\\s");
    private static final Pattern TEST_ANNOTATION_FIRST = Pattern.compile(
            "\\A(?:\\s*@(?!Test\\b|ParameterizedTest\\b|Property\\b)\\w+(?:\\([^)]*\\))?)*\\s*@(?:Test|ParameterizedTest|Property)\\b");

    record Scenario(String id, String title) {
    }

    /** Scenarios whose test does not carry their identifier: identifier to {@code Class#method} references. */
    private static final Map<String, List<String>> UNTAGGED = Map.ofEntries(
            Map.entry("BI60", List.of("SchedulerStarterCompatibilityTest#theWorkerHoldsExactlyOneSchedulerAndItIsTheOneItDeclares",
                    "WorkerHasNoMeterRegistryTest#noProcessHoldsAMeterRegistry")),
            Map.entry("BI65", List.of("RolePrivilegeMatrixIT#scheduledTasksPrivilegesAreExactlyTheMatrixForTheFiveRoles")),
            Map.entry("BI67", List.of("RolePrivilegeMatrixIT#scheduledTasksPrivilegesAreExactlyTheMatrixForTheFiveRoles")),
            Map.entry("BI68", List.of("RolePrivilegeMatrixIT#thePurgeFunctionsAreOwnedByTheOwnerRunWithItsRightsAndPinTheirSearchPathAndTimeZone",
                    "RolePrivilegeMatrixIT#executeOnThePurgeFunctionsIsHeldByTheAdministrativeRoleAndTheOwnerOnly")),
            Map.entry("BI69", List.of("RolePrivilegeMatrixIT#publicAndAnyRoleOutsideTheFiveCannotExecuteThePurgeFunctions")),
            Map.entry("BI94", List.of("ProcessBeanInspectorTest#reportsABeanOutsideTheAllowListNamingTheBeanAndItsPackage",
                    "ProcessBeanIsolationTest#registersOnlyItsAllowedBeans")),
            Map.entry("BI102", List.of("StarterAutoConfigurationExcludedTest#anAutoConfigurationNobodyExcludedIsReportedByName")),
            Map.entry("BI103", List.of("WorkerProductionWiringTest#theRealWorkerHasADataSourceAndTheGuardIsActiveInItsContext")),
            Map.entry("BJ08", List.of("ProcessBeanIsolationTest#aSchedulerInTheAdministrativeProcessIsReportedNamingProcessAndBean",
                    "StarterAutoConfigurationExcludedTest#anAutoConfigurationNobodyExcludedIsReportedByName")),
            Map.entry("I264", List.of("RolePrivilegeMatrixIT#noRoleOtherThanTheOwnerCanDeleteFromTheTablesThePurgeFunctionsServe")));

    private static final Map<String, List<String>> BY_TITLE = Map.ofEntries(
            Map.entry("El verificador se invoca directamente, sin infraestructura de programación",
                    List.of("AuditChainVerifierIT#anIntactChainReportsIntegrityAndIdentifiesNoRow",
                            "AuditChainVerifierIT#aRowAlteredDirectlyWithSuperuserWithoutRecalculatingIsIdentifiedExactly")),
            Map.entry("SQL plano fuera de la lista aprobada", List.of("NoUnapprovedPlainSqlTest#rejectsTheFixturePlainSqlUsage")),
            Map.entry("La matriz de privilegios cubre la tabla nueva para los cinco roles",
                    List.of("RolePrivilegeMatrixIT#confiaAdminAppCanSelectInsertAndUpdateButNeverDeleteOnThePasswordResetTokenTable",
                            "RolePrivilegeMatrixIT#confiaPortalAppHasNoPrivilegeOnThePasswordResetTokenTable",
                            "RolePrivilegeMatrixIT#confiaReadonlyOnlySelectsThePasswordResetTokenTable",
                            "RolePrivilegeMatrixIT#confiaBackupCanOnlySelectThePasswordResetTokenTableThroughPgReadAllData",
                            "RolePrivilegeMatrixIT#confiaOwnerRetainsAllPrivilegesOnThePasswordResetTokenTableByDefinition")),
            Map.entry("`confia_admin_app` puede leer, insertar y actualizar, pero no borrar",
                    List.of("RolePrivilegeMatrixIT#confiaAdminAppCanSelectInsertAndUpdateButNeverDeleteOnThePasswordResetTokenTable")),
            Map.entry("`confia_portal_app` no tiene ningún privilegio sobre la tabla nueva",
                    List.of("RolePrivilegeMatrixIT#confiaPortalAppHasNoPrivilegeOnThePasswordResetTokenTable")),
            Map.entry("El portal arrastra un punto de entrada ajeno",
                    List.of("ProcessBeanInspectorTest#reportsANominallyForbiddenBeanNamingThePackageAndTheReason")),
            Map.entry("El portal arrastra un módulo administrativo", List.of(
                    "ProcessBeanInspectorTest#reportsAnAdministrativeModuleInThePortalThroughItsNamedEntryNotOnlyTheAllowList")),
            Map.entry("El portal contiene solo lo suyo", List.of(
                    "PortalContextStartupTest#thePortalContainsNoBeanOfIdentityOrOrganizationOrAnyAdministrativeWiring")),
            Map.entry("El portal no hereda el cableado administrativo pero sí tiene su cableado de programación",
                    List.of("ProcessBeanIsolationTest#thePortalDoesNotInheritTheAdministrativeWiringButHasItsSchedulingWiring")),
            Map.entry("El trabajador recibe una importación ajena",
                    List.of("ProcessBeanInspectorTest#reportsTheOpenApiSurfaceInTheWorkerAsForbidden")),
            Map.entry("El trabajador arranca sin servidor web y con su cableado de base de datos",
                    List.of("ProcessBeanIsolationTest#registersOnlyItsAllowedBeans",
                            "WorkerProductionWiringTest#theWorkerHoldsItsNarrowDataAccessAndNothingAdministrative")),
            Map.entry("El trabajador no recibe el borde web",
                    List.of("ProcessBeanIsolationTest#registersOnlyItsAllowedBeans",
                            "ProcessBeanIsolationTest#aMetricsBeanOfTheAdministrativeEdgeIsRefusedInTheWorkerAndThePortalOnly")),
            Map.entry("Un punto de entrada vuelve a escanear",
                    List.of("BootstrapEntryPointRulesTest#rejectsTheFixtureEntryPointThatScansComponents")),
            Map.entry("Los tres puntos de entrada cumplen la forma explícita",
                    List.of("BootstrapEntryPointRulesTest#productionEntryPointsDoNotScanComponents")),
            Map.entry("El proceso administrativo arranca con la base de datos inalcanzable",
                    List.of("AdminProductionWiringTest#theAdminPoolIsLazyAndHasNotConnectedAfterStartup",
                            "AdminProductionWiringTest#theAdminContextRunsNoMigrationAtStartup")),
            Map.entry("El trabajador y el portal tienen `DataSource`, y solo el trabajador tiene `DSLContext`",
                    List.of("ProcessBeanIsolationTest#theWorkerAndThePortalHaveADataSourceAndOnlyTheWorkerAndAdminHaveADslContext")),
            Map.entry("Las pruebas de arranque no necesitan Docker",
                    List.of("ProcessBeanIsolationTest#registersOnlyItsAllowedBeans")),
            Map.entry("Ninguna DEK retirada se recifra automáticamente",
                    List.of("IdentityScopeExclusionInventoryTest#noScheduledJobReencryptsARetiredDataEncryptionKeyYet",
                            "IdentityScopeExclusionInventoryTest#aRegisteredJobThatRotatesADataEncryptionKeyIsDetected")),
            Map.entry("El puerto de envío solo tiene el emisor que falla de forma explícita", List.of(
                    "IdentityScopeExclusionInventoryTest#theIssuanceSchedulerHasOnlyTheJobAdapterAndTheLinkSenderHasOnlyTheOneThatFailsExplicitly")),
            Map.entry("Un restablecimiento completado no notifica a nadie",
                    List.of("IdentityScopeExclusionInventoryTest#aCompletedResetNotifiesNoOneAndAuditsNoDelivery")));

    /** Scenarios no test covers, with why. They are build rules or have no runnable fixture; they are findings, not mappings. */
    private static final Map<String, String> WITHOUT_A_TEST = Map.of(
            "Se agrega Hibernate", "the maven-enforcer bannedDependencies rule of apps/api/pom.xml fails ./mvnw verify; "
                    + "no test adds Hibernate to a POM",
            "Sin dependencias prohibidas", "the same enforcer rule passing on every ./mvnw verify; no test of its own");

    // --- the checks ---------------------------------------------------------------------------

    @Test
    void everyScenarioWithAnIdentifierIsNamedByATestOrListedWithTheTestThatCoversIt() {
        List<Scenario> scenarios = deltaScenarios();
        assertThat(scenarios).as("non-vacuous: 241 scenarios in the four deltas").hasSize(241);
        assertThat(untraced(scenarios, testsNaming().keySet(), UNTAGGED.keySet()))
                .as("scenarios no test names and no entry covers").isEmpty();
    }

    @Test
    void anEntryOfTheUntaggedListIsNeededAndAnIdentifierThatNoScenarioHasIsRejected() {
        Set<String> ids = new HashSet<>();
        deltaScenarios().stream().filter(s -> s.id() != null).forEach(s -> ids.add(s.id()));
        assertThat(UNTAGGED.keySet()).as("identifiers of the deltas").isSubsetOf(ids);
        assertThat(UNTAGGED.keySet()).as("a test that now names the scenario makes the entry dead weight")
                .doesNotContainAnyElementsOf(testsNaming().keySet());
    }

    @Test
    void everyScenarioWithoutAnIdentifierIsMappedByTitleOrListedAsWithoutATest() {
        Set<String> titles = new HashSet<>();
        deltaScenarios().stream().filter(s -> s.id() == null).forEach(s -> titles.add(s.title()));
        assertThat(titles).as("non-vacuous").hasSize(22);
        Set<String> accounted = new HashSet<>(BY_TITLE.keySet());
        accounted.addAll(WITHOUT_A_TEST.keySet());
        assertThat(accounted).as("the unnamed scenarios of the deltas, no more and no fewer").isEqualTo(titles);
    }

    @Test
    void everyReferenceNamesAClassAndAMethodThatExist() {
        Map<String, String> sources = testSources();
        assertThat(sourceOf(Map.of("a/Dup", "x", "b/Dup", "y"), "Dup")).as("a name shared by two files is ambiguous").isNull();
        List<String> broken = new ArrayList<>();
        Stream.concat(UNTAGGED.values().stream(), BY_TITLE.values().stream()).flatMap(List::stream).forEach(reference -> {
            String[] parts = reference.split("#");
            String source = sourceOf(sources, parts[0]);
            if (source == null || !source.contains("void " + parts[1] + "(")) {
                broken.add(reference);
            }
        });
        assertThat(broken).as("references to a class or a method that does not exist").isEmpty();
    }

    @Test
    void aScenarioNoTestNamesIsReported() {
        List<Scenario> scenarios = List.of(new Scenario("BJ01", "named"), new Scenario("ZZ99", "invented"),
                new Scenario("ZZ98", "covered by an entry"));
        assertThat(untraced(scenarios, Set.of("BJ01"), Set.of("ZZ98"))).containsExactly("ZZ99");
    }

    @Test
    void aMentionOnlyInACommentAHelperAHarnessOrWithoutTheCueDoesNotCount() {
        Map<String, String> sources = Map.of(
                "com/confia/x/SomeHarness", """
                        /** Harness of BJ37. */
                        class SomeHarness { }
                        """,
                "com/confia/x/OtherTest", """
                        /**
                         * Proves something else; see BJ38 for the neighbour.
                         */
                        class OtherTest {
                            /** Helper for BJ39. */
                            void helper() { }

                            @Test
                            void aTest() {
                                // BJ40 is mentioned here
                            }
                        }
                        """,
                "com/confia/x/RealTest", """
                        /** Proves scenarios BJ50 and BI70 to BI72. */
                        class RealTest {
                            /** Scenario BJ51. */
                            @Test
                            void one() { }

                            @Test
                            @DisplayName("BJ52 holds")
                            void two() { }
                        }
                        """);
        assertThat(testsNaming(sources).keySet()).containsExactlyInAnyOrder("BJ50", "BJ51", "BJ52", "BI70", "BI71",
                "BI72");
    }

    // --- reading ------------------------------------------------------------------------------

    static List<String> untraced(List<Scenario> scenarios, Set<String> named, Set<String> listed) {
        return scenarios.stream().map(Scenario::id).filter(id -> id != null && !named.contains(id) && !listed.contains(id))
                .toList();
    }

    private static List<Scenario> deltaScenarios() {
        Path specs = repoRoot().resolve(SPECS);
        assumeTrue(Files.isDirectory(specs), "the change was archived: its delta specs live in openspec/specs now");
        List<Scenario> scenarios = new ArrayList<>();
        try (Stream<Path> files = Files.walk(specs)) {
            for (Path file : files.filter(f -> f.getFileName().toString().equals("spec.md")).sorted().toList()) {
                for (String line : Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").lines().toList()) {
                    Matcher matcher = SCENARIO.matcher(line);
                    if (matcher.matches()) {
                        scenarios.add(new Scenario(matcher.group(1), matcher.group(2).trim()));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return scenarios;
    }

    /** Identifier to the test classes that name it; see the rule in the Javadoc of this class. This class is left out. */
    private static Map<String, Set<String>> testsNaming() {
        return testsNaming(testSources());
    }

    static Map<String, Set<String>> testsNaming(Map<String, String> sources) {
        Map<String, Set<String>> naming = new TreeMap<>();
        sources.forEach((path, source) -> {
            if (path.endsWith("/" + JobsChangeTraceabilityTest.class.getSimpleName()) || !TEST_METHOD.matcher(source).find()) {
                return;
            }
            for (String text : taggingTexts(source)) {
                Matcher range = RANGE.matcher(text);
                while (range.find()) {
                    for (int n = Integer.parseInt(range.group(2)); n <= Integer.parseInt(range.group(3)); n++) {
                        naming.computeIfAbsent("%s%02d".formatted(range.group(1), n), k -> new HashSet<>()).add(path);
                    }
                }
                Matcher tag = TAG.matcher(text);
                while (tag.find()) {
                    naming.computeIfAbsent(tag.group(1) + tag.group(2), k -> new HashSet<>()).add(path);
                }
            }
        });
        return naming;
    }

    /**
     * The texts of a test class where a mention is a tag: every {@code @DisplayName}, and, in the Javadoc of a type or of
     * a method annotated as a test, each sentence from its cue word ("scenario(s)", "spec(s)") to its end.
     */
    private static List<String> taggingTexts(String source) {
        List<String> texts = new ArrayList<>();
        Matcher displayName = DISPLAY_NAME.matcher(source);
        while (displayName.find()) {
            texts.add(displayName.group(1));
        }
        Matcher javadoc = JAVADOC.matcher(source);
        while (javadoc.find()) {
            String declaration = source.substring(javadoc.end());
            if (TYPE_DECLARATION.matcher(declaration).find() || TEST_ANNOTATION_FIRST.matcher(declaration).find()) {
                for (String sentence : javadoc.group(1).replaceAll("(?m)^\\s*\\*", " ").replaceAll("\\s+", " ").split("(?<=\\.)\\s")) {
                    Matcher cue = CUE.matcher(sentence);
                    if (cue.find()) {
                        texts.add(sentence.substring(cue.start()));
                    }
                }
            }
        }
        return texts;
    }

    /** Path under the test tree of {@code app}, without {@code .java}, to the source text; no two files share a key. */
    private static Map<String, String> testSources() {
        Path tree = repoRoot().resolve("apps/api/app/src/test/java");
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(tree)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String path = tree.relativize(file).toString().replace('\\', '/');
                sources.put(path.substring(0, path.length() - ".java".length()), Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sources;
    }

    /** The source of the class a reference names, by simple name; an ambiguous or missing name is {@code null}. */
    static String sourceOf(Map<String, String> sources, String simpleName) {
        List<String> found = sources.keySet().stream().filter(path -> path.equals(simpleName) || path.endsWith("/" + simpleName)).toList();
        return found.size() == 1 ? sources.get(found.get(0)) : null;
    }

    private static Path repoRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("docs/adr"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find the repository root from " + Paths.get("").toAbsolutePath());
    }
}
