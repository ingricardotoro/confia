package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.bootstrap.worker.WorkerApplication;
import com.confia.organization.application.InstitutionRepository;
import com.confia.shared.jobs.JobCalendar;
import com.confia.shared.jobs.JobDefinition;
import com.confia.shared.jobs.RecurringJob;
import com.confia.shared.jobs.RegisteredJobs;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import confia.generated.jooq.Public;
import confia.generated.jooq.tables.OrganizationInstitution;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The absences that hold today and stop being true on a named day (scenarios BJ38, BJ39, BJ40 and AT17 of
 * background-jobs-with-db-scheduler, task 4.3). Each one has two halves: the check against the real tree or
 * context, and a fixture the same check must report, so that a check that stopped looking would not pass
 * for lack of anything to find. Each carries the reason it expires, written in its constant: the day the
 * owner change closes, the check is rewritten or deleted by that change, never silenced.
 */
class CurrentAbsencesTest {

    static final String BJ38_EXPIRES = "BJ38 stops being true when changes 10 and 11 add the metrics exporters";
    static final String BJ38_HEALTH_ONLY =
            "BJ38: Actuator is admitted as the health module only (dockerfiles-and-local-compose, probe P-1)";
    static final String BJ39_EXPIRES = "BJ39 stops being true when phase 2 delivers the discovery of institutions";
    static final String BJ40_EXPIRES = "BJ40 stops being true when each module that owns a business job delivers it";
    static final String AT17_EXPIRES =
            "AT17 stops being true when audit-chain-hourly-anchor-verification closes (depends on changes 9 and 11)";

    /** What no pom may declare: the metrics starter, the Micrometer modules and any Prometheus artifact. */
    private static final Pattern EXPORTER_ARTIFACT = Pattern.compile(
            "micrometer-.*|spring-boot-starter-actuator|spring-boot-micrometer-.*|.*prometheus.*");

    /** Every artifact that belongs to the Actuator family, whatever it is for. */
    private static final Pattern ACTUATOR_ARTIFACT = Pattern.compile("spring-boot-(starter-)?actuator.*|spring-boot-health");

    /** The Actuator artifacts the health probes need and nothing more (probe P-1). */
    static final Set<String> HEALTH_ARTIFACTS = Set.of("spring-boot-health", "spring-boot-actuator-autoconfigure");

    /** The one-time task and the eight recurring ones this change registers, and nothing else. */
    private static final Set<String> THE_JOBS_OF_THIS_CHANGE = Set.of("identity.password-reset-issuance",
            "shared.audit-chain-verification-daily", "shared.audit-chain-verification-weekly",
            "identity.purge-password-reset-tokens", "identity.purge-refresh-tokens", "shared.purge-idempotency-keys",
            "identity.purge-login-backoff", "identity.purge-mfa-totp-backoff", "shared.queue-observation");

    private static List<JobDefinition> workerJobs;

    @BeforeAll
    static void readTheRegistryOfTheRealWorker() {
        workerJobs = RegisteredJobs.ofTheWorker();
    }

    // --- BJ38: no exporters in the tree ---------------------------------------------------------

    static List<String> exporterDependencies(String pomXml) throws Exception {
        return declaredArtifacts(pomXml, EXPORTER_ARTIFACT);
    }

    static List<String> actuatorDependencies(String pomXml) throws Exception {
        return declaredArtifacts(pomXml, ACTUATOR_ARTIFACT);
    }

    private static List<String> declaredArtifacts(String pomXml, Pattern wanted) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList dependencies = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(pomXml.getBytes(StandardCharsets.UTF_8)))
                .getElementsByTagName("dependency");
        List<String> found = new ArrayList<>();
        for (int i = 0; i < dependencies.getLength(); i++) {
            NodeList artifact = ((Element) dependencies.item(i)).getElementsByTagName("artifactId");
            String name = artifact.getLength() == 0 ? "" : artifact.item(0).getTextContent().trim();
            if (wanted.matcher(name).matches()) {
                found.add(name);
            }
        }
        return found;
    }

    static List<String> meterRegistries(ConfigurableApplicationContext context) {
        return List.of(context.getBeanNamesForType(MeterRegistry.class, true, false));
    }

    @Test
    void noPomOfTheApiDeclaresAMetricsDependencyDirectly() throws Exception {
        Path api = repoRoot().resolve("apps/api");
        for (String pom : List.of("pom.xml", "app/pom.xml", "kernel/pom.xml")) {
            assertThat(exporterDependencies(Files.readString(api.resolve(pom), StandardCharsets.UTF_8)))
                    .as("%s: %s", pom, BJ38_EXPIRES).isEmpty();
        }
    }

    @Test
    void theOnlyActuatorArtifactsTheApiDeclaresAreTheHealthOnes() throws Exception {
        Path api = repoRoot().resolve("apps/api");
        Set<String> declared = new TreeSet<>();
        for (String pom : List.of("pom.xml", "app/pom.xml", "kernel/pom.xml")) {
            declared.addAll(actuatorDependencies(Files.readString(api.resolve(pom), StandardCharsets.UTF_8)));
        }
        assertThat(declared).as(BJ38_HEALTH_ONLY).containsExactlyInAnyOrderElementsOf(HEALTH_ARTIFACTS);
    }

    @Test
    void aPomThatDeclaresTheMetricsStarterOrAPrometheusRegistryIsReported() throws Exception {
        String pom = "<project><dependencies>"
                + "<dependency><groupId>g</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>"
                + "<dependency><groupId>g</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>"
                + "<dependency><groupId>g</groupId><artifactId>spring-boot-micrometer-metrics</artifactId></dependency>"
                + "<dependency><groupId>g</groupId><artifactId>jooq</artifactId></dependency>"
                + "</dependencies></project>";
        assertThat(exporterDependencies(pom)).containsExactly("spring-boot-starter-actuator",
                "micrometer-registry-prometheus", "spring-boot-micrometer-metrics");
    }

    @Test
    void anActuatorArtifactBeyondTheHealthOnesIsReported() throws Exception {
        String pom = "<project><dependencies>"
                + "<dependency><groupId>g</groupId><artifactId>spring-boot-health</artifactId></dependency>"
                + "<dependency><groupId>g</groupId><artifactId>spring-boot-actuator-autoconfigure</artifactId></dependency>"
                + "<dependency><groupId>g</groupId><artifactId>spring-boot-actuator</artifactId></dependency>"
                + "<dependency><groupId>g</groupId><artifactId>jooq</artifactId></dependency>"
                + "</dependencies></project>";
        assertThat(actuatorDependencies(pom)).containsExactly("spring-boot-health",
                "spring-boot-actuator-autoconfigure", "spring-boot-actuator");
        assertThat(exporterDependencies(pom)).as("the health artifacts are not exporters").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"admin", "portal", "worker"})
    void noProcessHoldsAMeterRegistry(String process) {
        LaunchOutcome outcome = ConfiaApplication.launch(TestProcessArguments.forProcess(process), process);
        ConfigurableApplicationContext context = outcome.context();
        assertThat(context).as("the %s process must start", process).isNotNull();
        try {
            assertThat(meterRegistries(context)).as("%s: %s", process, BJ38_EXPIRES).isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void aMeterRegistryInAContextIsReported() {
        try (AnnotationConfigApplicationContext fixture = new AnnotationConfigApplicationContext()) {
            fixture.registerBean("rogueRegistry", MeterRegistry.class, SimpleMeterRegistry::new);
            fixture.refresh();
            assertThat(meterRegistries(fixture)).containsExactly("rogueRegistry");
        }
    }

    // --- BJ39: the worker does not read the institutions table ----------------------------------

    /** Fixture: a class of the worker's reach that imports the organization module. */
    static final class ImportsTheOrganizationModule {
        InstitutionRepository repository;
    }

    /** Fixture: a class of the worker's reach that names the institutions table. */
    static final class ReadsTheInstitutionsTable {
        Object table() {
            return OrganizationInstitution.ORGANIZATION_INSTITUTION;
        }
    }

    /**
     * Fixture: a class of the worker's reach that reaches the institutions table through the jOOQ schema
     * object, which lists every table, without naming {@code OrganizationInstitution}.
     */
    static final class ReadsThroughTheSchema {
        Object tables() {
            return Public.PUBLIC.getTables();
        }
    }

    /**
     * The organization module, the generated institutions table, and the generated objects that list every
     * table. The build generates no {@code Tables} umbrella class (see {@code globalObjectReferences} in
     * {@code apps/api/app/pom.xml}); the schema {@code Public} and the {@code DefaultCatalog} are the global
     * references that exist, and {@code Tables} stays in the list so that turning the option on does not open a gap.
     */
    private static boolean institutionSide(String className) {
        return className.startsWith("com.confia.organization.")
                || className.startsWith("confia.generated.jooq.tables.OrganizationInstitution")
                || Set.of("confia.generated.jooq.Public", "confia.generated.jooq.DefaultCatalog",
                        "confia.generated.jooq.Tables").contains(className);
    }

    /** The classes of {@code com.confia} that {@code root} reaches through its dependencies, itself included. */
    static Set<JavaClass> reach(JavaClass root) {
        Set<JavaClass> seen = new HashSet<>(Set.of(root));
        Deque<JavaClass> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            pending.pop().getDirectDependenciesFromSelf().forEach(dependency -> {
                JavaClass target = dependency.getTargetClass();
                if (target.getFullName().startsWith("com.confia.") && !institutionSide(target.getFullName())
                        && seen.add(target)) {
                    pending.push(target);
                }
            });
        }
        return seen;
    }

    /** Every dependency, of {@code root} or of what it reaches, on the organization module or the institutions table. */
    static List<String> institutionAccess(JavaClass root) {
        return reach(root).stream().flatMap(reached -> reached.getDirectDependenciesFromSelf().stream()
                .filter(dependency -> institutionSide(dependency.getTargetClass().getFullName()))
                .map(dependency -> reached.getName() + " -> " + dependency.getTargetClass().getFullName())).toList();
    }

    @Test
    void nothingTheWorkerReachesReadsTheInstitutionsTableOrImportsTheOrganizationModule() {
        JavaClasses production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.confia");
        assertThat(production.contain(WorkerApplication.class)).as("non-vacuous: the root is imported").isTrue();
        assertThat(reach(production.get(WorkerApplication.class)).stream().map(JavaClass::getName))
                .as("non-vacuous: the traversal reaches the wiring and the jobs of the worker")
                .contains("com.confia.shared.jobs.infrastructure.wiring.WorkerJobsConfiguration",
                        "com.confia.shared.audit.AuditChainVerificationJobs",
                        "com.confia.identity.application.PurgeStaleBackoff");
        assertThat(institutionAccess(production.get(WorkerApplication.class)))
                .as("the worker reads the institutions from its configuration: %s", BJ39_EXPIRES).isEmpty();
    }

    @Test
    void aClassThatImportsTheOrganizationModuleOrNamesTheInstitutionsTableIsReported() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(ImportsTheOrganizationModule.class,
                ReadsTheInstitutionsTable.class);
        assertThat(institutionAccess(fixtures.get(ImportsTheOrganizationModule.class))).singleElement().asString()
                .contains("com.confia.organization.application.InstitutionRepository");
        assertThat(institutionAccess(fixtures.get(ReadsTheInstitutionsTable.class))).singleElement().asString()
                .contains("confia.generated.jooq.tables.OrganizationInstitution");
    }

    @Test
    void aReadThroughTheSchemaObjectThatListsEveryTableIsReported() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(ReadsThroughTheSchema.class);
        assertThat(institutionAccess(fixtures.get(ReadsThroughTheSchema.class))).isNotEmpty()
                .allSatisfy(access -> assertThat(access).contains("confia.generated.jooq.Public"));
    }

    @Test
    void theBuildGeneratesNoTablesUmbrellaClassSoNoFixtureReadsThroughIt() {
        assertThatThrownBy(() -> Class.forName("confia.generated.jooq.Tables"))
                .as("if this class exists, add a fixture that reads the institutions table through it")
                .isInstanceOf(ClassNotFoundException.class);
    }

    // --- BJ40: the registry holds only the types of this change ---------------------------------

    static List<String> registryDifference(List<JobDefinition> registry) {
        Set<String> registered = new TreeSet<>(registry.stream().map(JobDefinition::name).toList());
        List<String> difference = new ArrayList<>();
        registered.stream().filter(name -> !THE_JOBS_OF_THIS_CHANGE.contains(name)).forEach(n -> difference.add("unexpected " + n));
        THE_JOBS_OF_THIS_CHANGE.stream().filter(name -> !registered.contains(name)).sorted().forEach(n -> difference.add("missing " + n));
        return difference;
    }

    private static RecurringJob recurring(String name, JobCalendar calendar) {
        return new RecurringJob(name, calendar, (instance, run) -> { });
    }

    @Test
    void theWorkerRegistersExactlyTheOneTimeTaskAndTheEightRecurringOnesOfThisChange() {
        assertThat(registryDifference(workerJobs)).as(BJ40_EXPIRES).isEmpty();
        assertThat(workerJobs).as("non-vacuous").hasSize(9);
    }

    @Test
    void aBusinessTypeThatNobodyDeclaredMakesTheEnumerationFail() {
        List<JobDefinition> withBilling = new ArrayList<>(workerJobs);
        withBilling.add(recurring("invoicing.generate-monthly-charges", new JobCalendar.DailyAt(LocalTime.of(2, 0))));
        assertThat(registryDifference(withBilling)).containsExactly("unexpected invoicing.generate-monthly-charges");
    }

    // --- AT17: no hourly cadence of the audit chain ---------------------------------------------

    static List<String> hourlyOrAnchorJobs(List<JobDefinition> registry) {
        return registry.stream().filter(job -> job.name().matches("(?i).*(hourly|anchor).*")
                || job instanceof RecurringJob recurring && job.name().startsWith("shared.audit-chain")
                        && recurring.calendar() instanceof JobCalendar.Every).map(JobDefinition::name).toList();
    }

    @Test
    void theWorkerRegistersNoHourlyCadenceOfTheChainVerifierAndNoAnchorComparison() {
        assertThat(workerJobs).as("non-vacuous").extracting(JobDefinition::name)
                .contains("shared.audit-chain-verification-daily", "shared.audit-chain-verification-weekly");
        assertThat(hourlyOrAnchorJobs(workerJobs)).as(AT17_EXPIRES).isEmpty();
    }

    @Test
    void anHourlyChainVerificationOrAnAnchorComparisonIsReported() {
        List<JobDefinition> withHourly = new ArrayList<>(workerJobs);
        withHourly.add(recurring("shared.audit-chain-verification-hourly", new JobCalendar.Every(Duration.ofHours(1))));
        withHourly.add(recurring("shared.audit-chain-external-comparison", new JobCalendar.Every(Duration.ofHours(2))));
        withHourly.add(recurring("shared.last-hash-anchor", new JobCalendar.DailyAt(LocalTime.of(5, 0))));
        assertThat(hourlyOrAnchorJobs(withHourly)).containsExactlyInAnyOrder(
                "shared.audit-chain-verification-hourly", "shared.audit-chain-external-comparison",
                "shared.last-hash-anchor");
    }

    private static Path repoRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("apps/api/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find the repository root from " + Paths.get("").toAbsolutePath());
    }
}
