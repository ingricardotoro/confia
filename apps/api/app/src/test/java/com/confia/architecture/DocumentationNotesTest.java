package com.confia.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Proves the dated editorial notes that ADR-0025 introduced (scenarios BJ41, BJ72): the ADR file and
 * its index row exist, ADR-0017, {@code docs/03} section 6.1 and {@code docs/08} section 6.2 carry a
 * dated note, and none of their original lines was rewritten. The originals are embedded as
 * fixtures under {@code src/test/resources/documentation-notes}, copies of the files as they were
 * before the notes; every fixture line must still appear, in order, in the live document.
 *
 * <p>It also proves the closing notes of task 4.2 of background-jobs-with-db-scheduler (scenarios BJ41,
 * BJ42, BJ70 and BJ71): the table of the eight recurring jobs, the parked failed state, the manual
 * reprogramming and the alert on the absence of {@code queue_state} in {@code docs/07}; the note of
 * {@code docs/05} section 6 that reverses the one of 2026-10-04; the dated note of the foundations plan;
 * and the Javadoc of {@code WorkerApplication}. The originals of those documents are embedded in this
 * class as text blocks, because their sections are long and later changes legitimately add notes to them:
 * the check is that every original line still appears, in order, never that the section is unchanged.
 */
class DocumentationNotesTest {

    private static final String ADR_0025 = "docs/adr/ADR-0025-purga-por-funciones-security-definer.md";
    private static final String ADR_0017 = "docs/adr/ADR-0017-tablas-tecnicas-y-tabla-raiz.md";
    private static final String DOCS_03 = "docs/03-seguridad.md";
    private static final String DOCS_08 = "docs/08-datos-privacidad-y-retencion.md";
    private static final String NOTE_MARKER = "**Nota editorial, 2026-10-29";
    private static final String ADR_NOTE_MARKER = "**Nota fechada, 2026-10-29";
    private static final String DOCS_05 = "docs/05-infraestructura-y-despliegue.md";
    private static final String DOCS_07 = "docs/07-observabilidad-y-operaciones.md";
    private static final String DOCS_09 = "docs/09-roadmap-y-fases.md";
    private static final String EXPLORATION = "openspec/changes/foundations-plan/exploration.md";
    private static final String WORKER_APPLICATION =
            "apps/api/app/src/main/java/com/confia/bootstrap/worker/WorkerApplication.java";
    private static final String CLOSING_NOTE =
            "**Nota fechada 2026-11-01 (cambio `background-jobs-with-db-scheduler`, tarea 4.2)";
    private static final String TRACED_JOB_NOTE =
            "**Nota fechada 2026-10-26 (cambio `background-jobs-with-db-scheduler`, tarea 2.1)";
    private static final String ADR_0027 = "docs/adr/ADR-0027-autorizacion-por-permisos-resueltos-por-peticion.md";
    private static final String ADR_0005 = "docs/adr/ADR-0005-autenticacion-y-gestion-de-sesiones.md";
    private static final String ADR_0005_NOTE_HEADING = "### Nota fechada 2026-11-04";
    /** SHA-256 of ADR-0005 as it was before this note: every line up to the new heading, each ended by a line feed. */
    private static final String ADR_0005_ORIGINAL_SHA256 =
            "d120e72d8f00a2650eea565352b5f292cbf4451250ca9d17f0ec304d03448162";
    private static final String RBAC_NOTE = "**Nota fechada 2026-11-04 (cambio "
            + "`rbac-permission-matrix-and-audit-integration`, PR 1 `permission-catalog-and-adr`)";

    @Test
    void adr0025ExistsAsProposedAndIsIndexed() {
        List<String> adr = lines(repoRoot().resolve(ADR_0025));
        assertThat(adr).contains("- **Estado:** Propuesto");

        List<String> readme = lines(repoRoot().resolve("docs/adr/README.md"));
        assertThat(readme)
                .anySatisfy(row -> assertThat(row)
                        .contains("(ADR-0025-purga-por-funciones-security-definer.md)")
                        .contains("| Propuesto |"));
    }

    @Test
    void adr0017HasDatedNoteAndUnchangedOriginalBody() {
        List<String> live = lines(repoRoot().resolve(ADR_0017));
        assertThat(String.join("\n", live)).contains(ADR_NOTE_MARKER).contains("ADR-0025");
        assertOriginalLinesIntact(ADR_0017, "ADR-0017.original.md", live);
    }

    @Test
    void docs03Section61HasDatedNoteAndUnchangedOriginalBody() {
        List<String> section = section(lines(repoRoot().resolve(DOCS_03)), "### 6.1 ");
        assertThat(String.join("\n", section)).contains(NOTE_MARKER).contains("ADR-0025");
        assertOriginalLinesIntact(DOCS_03 + " section 6.1", "docs-03-section-6.1.original.md", section);
    }

    @Test
    void docs08Section62HasDatedNoteAndUnchangedOriginalBody() {
        List<String> section = section(lines(repoRoot().resolve(DOCS_08)), "### 6.2 ");
        assertThat(String.join("\n", section)).contains(NOTE_MARKER).contains("ADR-0025");
        assertOriginalLinesIntact(DOCS_08 + " section 6.2", "docs-08-section-6.2.original.md", section);
    }

    @Test
    void docs07Section33HasTheTracedJobNoteAndUnchangedOriginalBody() {
        List<String> section = section(lines(repoRoot().resolve(DOCS_07)), "### 3.3 ");
        assertThat(String.join("\n", noteBlock(section, TRACED_JOB_NOTE)))
                .contains("TracedJob")
                .contains("com.confia.shared.jobs");
        assertOriginalLinesIntact(DOCS_07 + " section 3.3", linesOf(ORIGINAL_DOCS_07_SECTION_3_3), section);
    }

    @Test
    void docs07Section11HasTheTableOfTheEightRecurringJobsAndTheParkedFailedState() {
        List<String> section = section(lines(repoRoot().resolve(DOCS_07)), "## 11. ");
        List<String> note = noteBlock(section, CLOSING_NOTE);
        String text = String.join("\n", note);
        assertThat(text).contains("America/Tegucigalpa");
        assertJobRow(note, "identity.purge-password-reset-tokens", "Diaria, 04:30");
        assertJobRow(note, "identity.purge-refresh-tokens", "Diaria, 04:35");
        assertJobRow(note, "shared.purge-idempotency-keys", "Diaria, 04:40");
        assertJobRow(note, "identity.purge-login-backoff", "Diaria, 04:45");
        assertJobRow(note, "identity.purge-mfa-totp-backoff", "Diaria, 04:50");
        assertJobRow(note, "shared.audit-chain-verification-daily", "Diaria, 03:00");
        assertJobRow(note, "shared.audit-chain-verification-weekly", "Domingo, 03:30");
        assertJobRow(note, "shared.queue-observation", "Cada 60 s");
        assertThat(text)
                .as("the parked failed state")
                .contains("9999-01-01T00:00:00Z")
                .contains("consecutive_failures")
                .contains("last_failure");
        assertThat(text)
                .as("per-institution occurrences belong to the recurring jobs, not to the one-time task")
                .contains("Las siete tareas recurrentes por institución")
                .contains("no tiene ocurrencias por institución")
                .doesNotContain("En ambos casos, una institución");
        assertOriginalLinesIntact(DOCS_07 + " section 11", linesOf(ORIGINAL_DOCS_07_SECTION_11), section);
    }

    @Test
    void docs07Section111HasTheManualReprogrammingAndTheAbsenceAlertAndUnchangedOriginalBody() {
        List<String> section = section(lines(repoRoot().resolve(DOCS_07)), "### 11.1 ");
        assertThat(String.join("\n", noteBlock(section, CLOSING_NOTE)))
                .contains("UPDATE scheduled_tasks")
                .contains("SET execution_time")
                .contains("consecutive_failures = 0")
                .contains("queue_state")
                .contains("5 minutos")
                .contains("misma cola")
                .as("the manual UPDATE names itself as the only exception to the 'nobody writes by hand' comment")
                .contains("única excepción");
        assertOriginalLinesIntact(DOCS_07 + " section 11.1", linesOf(ORIGINAL_DOCS_07_SECTION_11_1), section);
    }

    @Test
    void docs05Section6NamesTheWorkerAndPortalUsersAndReversesTheNoteOf20261004() {
        List<String> section = section(lines(repoRoot().resolve(DOCS_05)), "## 6. ");
        assertThat(String.join("\n", noteBlock(section, CLOSING_NOTE)))
                .contains("confia_admin_app")
                .contains("confia_portal_app")
                .contains("CONFIA_JOBS_INSTITUTIONS")
                .contains("2026-10-04")
                .contains("web-edge-foundations")
                .contains("D5");
        assertOriginalLinesIntact(DOCS_05 + " section 6 note of 2026-10-04",
                linesOf(ORIGINAL_DOCS_05_NOTE_2026_10_04), section);
    }

    @Test
    void foundationsPlanClosesCondition4AndRecordsTheFollowUpsAndTheDebtOfPhase2() {
        List<String> all = lines(repoRoot().resolve(EXPLORATION));
        assertThat(String.join("\n", noteBlock(all, CLOSING_NOTE)))
                .contains("condición 4")
                .contains("cerrada")
                .contains("audit-chain-hourly-anchor-verification")
                .contains("cambios 9 y 11")
                .contains("data-encryption-key-batch-rotation")
                .contains("cambio 9")
                .contains("fase 2")
                .contains("CONFIA_JOBS_INSTITUTIONS");
        assertOriginalLinesIntact(EXPLORATION + " condition 4", linesOf(ORIGINAL_EXPLORATION_CONDITION_4), all);
    }

    /**
     * Scenario BJ41 (task 4.3): exit criterion 5 of phase 0 is checked with a dated note that names the
     * tests that prove it, and the criteria around it are left as they were. The exit criterion itself (BJ37) is
     * proved by {@code ExitCriterion5EndToEndIT}; this test only checks that the note names it.
     */
    @Test
    void docs09MarksExitCriterion5OfPhase0WithADatedNoteThatNamesItsTests() {
        List<String> all = lines(repoRoot().resolve(DOCS_09));
        String criterion = "Una tarea programada dentro de una transacción revertida no existe, y solo el proceso "
                + "`confia-worker` ejecuta tareas.";
        int at = all.indexOf("- [x] " + criterion);
        assertThat(at).as("criterion 5 is checked and its text is unchanged").isNotNegative();
        List<String> note = new ArrayList<>();
        for (int i = at + 1; i < all.size() && all.get(i).startsWith("  "); i++) {
            note.add(all.get(i));
        }
        assertThat(String.join("\n", note))
                .contains("2026-11-01")
                .contains("background-jobs-with-db-scheduler")
                .contains("JobSchedulingAtomicityIT")
                .contains("OnlyWorkerExecutesIT")
                .contains("ExitCriterion5EndToEndIT");
        assertThat(all).as("the backup and accessibility criteria stay open")
                .contains("- [ ] **Un respaldo se restaura en un entorno limpio y el acta de simulacro está firmada.**",
                        "- [ ] La verificación de accesibilidad no reporta violaciones críticas en las pantallas existentes.");
    }

    @Test
    void workerApplicationJavadocDescribesTheDataSourceTheEarlyGuardsTheSingleExecutorAndTheNarrowWiring() {
        String javadoc = classJavadoc(repoRoot().resolve(WORKER_APPLICATION));
        assertThat(javadoc)
                .as("the DataSource of the worker")
                .contains("DataSource")
                .contains("confia_admin_app")
                .contains("SPRING_DATASOURCE_URL");
        assertThat(javadoc)
                .as("the guards that act before the context exists")
                .contains("SensitiveLogGuardInitializer")
                .contains("SigningKeyPlacementGuard");
        assertThat(javadoc)
                .as("the single executor")
                .contains("exactly one executor")
                .contains("WorkerJobsConfiguration");
        assertThat(javadoc)
                .as("the narrow wiring")
                .contains("SharedWorkerJobsConfiguration")
                .contains("IdentityWorkerConfiguration")
                .contains("CONFIA_JOBS_INSTITUTIONS")
                .contains("ProcessBeanPolicy");
    }

    /**
     * Scenarios AZ16 and AZ72 (rbac-permission-matrix-and-audit-integration, task 1): ADR-0027 exists as
     * proposed with the sections of its design, and the index lists it.
     */
    @Test
    void adr0027ExistsAsProposedWithItsSectionsAndIsIndexed() {
        List<String> adr = lines(repoRoot().resolve(ADR_0027));
        assertThat(adr).contains("- **Estado:** Propuesto");
        assertThat(adr).anySatisfy(line -> assertThat(line).startsWith("- **Fecha:** 2026-11-04"));
        assertThat(adr).contains("## Contexto y problema", "## Decisión", "## Partes de ADR-0005 que reemplaza",
                "## Alternativas descartadas", "## Consecuencias", "## Cómo se verifica");
        assertThat(String.join("\n", adr)).contains("`docs/03`").doesNotContain("<Título>");

        List<String> readme = lines(repoRoot().resolve("docs/adr/README.md"));
        assertThat(readme)
                .anySatisfy(row -> assertThat(row)
                        .startsWith("| [0027](ADR-0027-autorizacion-por-permisos-resueltos-por-peticion.md) |")
                        .contains("| Propuesto | 2026-11-04 |"));
    }

    @Test
    void adr0005HasTheDatedNoteThatPointsToAdr0027AndItsOriginalBodyIsIntact() {
        List<String> live = lines(repoRoot().resolve(ADR_0005));
        int note = indexOfLineStartingWith(live, ADR_0005_NOTE_HEADING);
        assertThat(note).as("the dated note of ADR-0005").isNotNegative();
        assertThat(String.join("\n", live.subList(note, live.size())))
                .contains("(ADR-0027-autorizacion-por-permisos-resueltos-por-peticion.md)")
                .contains("diez minutos")
                .contains("Auditor")
                .contains("Coordinador Académico")
                .contains("verificación 9");

        List<String> original = new ArrayList<>(live.subList(0, note));
        while (original.getLast().isBlank()) {
            original.removeLast();
        }
        assertThat(sha256(original.stream().map(line -> line + "\n").collect(Collectors.joining())))
                .as("the original body of ADR-0005, up to its dated notes, is not rewritten")
                .isEqualTo(ADR_0005_ORIGINAL_SHA256);
    }

    @Test
    void docs03Section51HasTheDatedNoteAndUnchangedOriginalBody() {
        List<String> section = section(lines(repoRoot().resolve(DOCS_03)), "### 5.1 ");
        assertThat(prose(noteBlock(section, RBAC_NOTE)))
                .contains("un rol por cuenta e institución")
                .contains("lista cerrada")
                .contains("por petición")
                .contains("ADR-0027");
        assertOriginalLinesIntact(DOCS_03 + " section 5.1", linesOf(ORIGINAL_DOCS_03_SECTION_5_1), section);
    }

    @Test
    void docs03Section52HasTheMatrixNoteWithTheFourDifferencesRule3TheClosingParagraphAndThePendingInitiators() {
        List<String> section = section(lines(repoRoot().resolve(DOCS_03)), "### 5.2 ");
        String note = prose(noteBlock(section, RBAC_NOTE));
        assertThat(note).as("the four differences")
                .contains("`invoicing:request-void`")
                .contains("`audit:read`")
                .contains("`payments:create`")
                .contains("MFA propia");
        assertThat(note).as("rule 3 rewritten")
                .contains("el Auditor es el único rol de solo lectura absoluta");
        assertThat(note).as("the closing paragraph")
                .contains("`StaffRole`")
                .contains("PermissionMatrixParityTest")
                .contains("migración semilla");
        assertThat(note).as("the initiators of section 5.3 without a permission, pending")
                .contains("ajuste manual del libro mayor")
                .contains("baja por incobrable")
                .contains("reembolso a un tercero")
                .contains("pendientes");
        assertOriginalLinesIntact(DOCS_03 + " section 5.2", linesOf(ORIGINAL_DOCS_03_SECTION_5_2), section);
    }

    @Test
    void docs03Section53HasTheDatedNoteOnTheQuarterlyReportAndUnchangedOriginalBody() {
        List<String> section = section(lines(repoRoot().resolve(DOCS_03)), "### 5.3 ");
        assertThat(prose(noteBlock(section, RBAC_NOTE)))
                .contains("revisa operaciones")
                .contains("pares de permisos")
                .contains("primera operación con aprobación");
        assertOriginalLinesIntact(DOCS_03 + " section 5.3", linesOf(ORIGINAL_DOCS_03_SECTION_5_3), section);
    }

    /** The text of a block quote as running prose: no quote marks and no line breaks, so a wrap never splits a phrase. */
    private static String prose(List<String> quote) {
        return quote.stream().map(line -> line.replaceFirst("^>\\s?", "")).collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ");
    }

    private static int indexOfLineStartingWith(List<String> all, String prefix) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertOriginalLinesIntact(String label, String fixtureName, List<String> live) {
        assertOriginalLinesIntact(label, fixture(fixtureName), live);
    }

    private static void assertOriginalLinesIntact(String label, List<String> original, List<String> live) {
        int cursor = 0;
        for (int i = 0; i < original.size(); i++) {
            String expected = original.get(i);
            int found = live.subList(cursor, live.size()).indexOf(expected);
            assertThat(found)
                    .as("The original body of %s changed: original line %d is missing or out of order: \"%s\"",
                            label, i + 1, expected)
                    .isGreaterThanOrEqualTo(0);
            cursor += found + 1;
        }
    }

    private static List<String> linesOf(String textBlock) {
        return textBlock.lines().toList();
    }

    /** The contiguous block quote (lines starting with {@code >}) that holds the line with {@code marker}. */
    private static List<String> noteBlock(List<String> all, String marker) {
        int at = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).startsWith(">") && all.get(i).contains(marker)) {
                at = i;
                break;
            }
        }
        assertThat(at).as("a block quote that carries \"%s\"", marker).isGreaterThanOrEqualTo(0);
        int from = at;
        while (from > 0 && all.get(from - 1).startsWith(">")) {
            from--;
        }
        int to = at;
        while (to + 1 < all.size() && all.get(to + 1).startsWith(">")) {
            to++;
        }
        return all.subList(from, to + 1);
    }

    private static void assertJobRow(List<String> note, String task, String calendar) {
        assertThat(note)
                .as("the row of %s with the calendar \"%s\"", task, calendar)
                .anySatisfy(row -> assertThat(row).startsWith("> | `" + task + "` |").contains(calendar));
    }

    /** The text of the Javadoc that precedes the class, without the comment markers. */
    private static String classJavadoc(Path file) {
        assertThat(file).as("file %s", file).exists();
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            int begin = source.indexOf("/**");
            String comment = source.substring(begin, source.indexOf("*/", begin));
            StringBuilder text = new StringBuilder();
            comment.lines().forEach(line -> text.append(line.strip().replaceFirst("^(/\\*\\*|\\*)\\s?", "")).append('\n'));
            return text.toString().replaceAll("\\s+", " ").strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Lines from the heading that starts with {@code headingPrefix} up to the next heading. */
    private static List<String> section(List<String> all, String headingPrefix) {
        List<String> result = new ArrayList<>();
        boolean inside = false;
        for (String line : all) {
            if (line.startsWith(headingPrefix)) {
                inside = true;
            } else if (inside && (line.startsWith("## ") || line.startsWith("### "))) {
                break;
            }
            if (inside) {
                result.add(line);
            }
        }
        assertThat(result).as("section %s", headingPrefix).isNotEmpty();
        return result;
    }

    private static List<String> fixture(String name) {
        return lines(Paths.get("src/test/resources/documentation-notes").toAbsolutePath().resolve(name));
    }

    private static List<String> lines(Path file) {
        assertThat(file).as("file %s", file).exists();
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    private static final String ORIGINAL_DOCS_07_SECTION_3_3 = """
        Los trabajos en segundo plano usan db-scheduler sobre PostgreSQL (ADR-0016). Lo que sigue es el
        contrato de correlación de sus tareas.

        Los datos de una tarea **solo llevan identificadores** (institución, entidad, clave de
        idempotencia), nunca importes ni datos personales (`docs/03-seguridad.md` sección 2.4). El
        contexto de correlación viaja como metadatos declarados dentro de esos datos.

        ```java
        // apps/api/app/src/main/java/<paquete-base>/shared/observability/context/TracedJob.java
        // Envelope of the db-scheduler task data (ADR-0016). Final shape and serialization fixed in F0.

        public record TracedJob<T>(T payload, JobMeta meta) {

            /** Every field except actorId and traceparent is mandatory. */
            public record JobMeta(
                    String correlationId,
                    String requestId,
                    String actorId,        // originating actor; null for system actors
                    ActorKind actorKind,   // originating actor kind: STAFF, GUARDIAN or SYSTEM
                    UUID institutionId,
                    String traceparent) {} // W3C Trace Context carrier
        }
        ```

        Reglas del contrato:

        - **Quien programa.** Nunca programa una tarea sin `meta`, y solo lo hace desde un caso de uso,
          dentro de la transacción del negocio. Toma `correlationId`, `requestId`, actor e institución del
          contexto de la solicitud en curso, y el `traceparent` del contexto de trazas activo. Una tarea
          programada sin solicitud de origen usa actor de sistema explícito.
        - **Manejador en `confia-worker`.** Antes de ejecutar, restaura el contexto de trazas desde
          `traceparent` y coloca en el MDC los campos de `meta` más `jobId`. El actor de `meta` se registra
          para correlación y auditoría, pero el manejador siempre ejecuta con actor `system` y el contexto
          de la institución de la tarea (ADR-0016, regla 4). Al terminar, limpia el MDC.

        **Cómo se comprueba.** Prueba de lista aprobada de campos por tipo de tarea: una tarea sin `meta` o
        con un `payload` que contenga campos no permitidos falla la validación de quien programa. Prueba de
        integración que programa una tarea desde una petición HTTP y verifica que el log del trabajador
        contiene el mismo `correlationId` que el log de la petición.
        """;

    private static final String ORIGINAL_DOCS_07_SECTION_11 = """
        Las tareas de aplicación de esta tabla son tareas recurrentes de db-scheduler (ADR-0016): se
        declaran en código con expresión de calendario y zona horaria `America/Tegucigalpa`, se ejecutan
        solo en `confia-worker` a través del componente transaccional con actor `system` y el contexto de
        cada institución, llevan `correlationId` propio y registran su resultado en la bitácora de
        auditoría cuando tocan datos. La tabla de tareas coordina la ejecución, de modo que una tarea
        recurrente corre una sola vez aunque haya varias réplicas del trabajador. Ninguna corre como `cron`
        del sistema operativo dentro del contenedor de la API ni con `@Scheduled` de Spring: eso duplicaría
        la ejecución en cada proceso. Las horas de la tabla son de `America/Tegucigalpa`.

        Las tareas de infraestructura de la tabla (respaldo lógico fuera de sitio, prueba de restauración,
        verificación de certificados y escaneo de la imagen) no son código de la aplicación y quedan fuera
        de ADR-0016: no corren en `confia-worker`, cuya imagen solo contiene el JRE y el artefacto. Se
        ejecutan mediante un temporizador de `systemd` en la instancia EC2 (`infra/scripts/backup.sh`,
        `docs/05-infraestructura-y-despliegue.md`, sección 11), validado en F0. Los respaldos automáticos
        de RDS con restauración a un punto en el tiempo los gestiona AWS directamente, sin una tarea que
        programar (ADR-0014).

        | Tarea | Frecuencia | Qué hace | Métrica que publica | Qué pasa si falla |
        |---|---|---|---|---|
        | **Verificación de integridad del libro mayor** | Diaria, 02:00 | Ejecuta las seis verificaciones de `.claude/skills/confia-ledger-invariants/SKILL.md` sección 7: cuadre por transacción, cuadre por cuenta contra la caché, imputación no excedida, unicidad de reverso, continuidad de auditoría y correlativo fiscal | `confia_ledger_unbalanced_transactions`, `confia_ledger_cached_balance_drift_accounts`, `confia_ledger_over_allocated_charges`, `confia_ledger_double_reversal_transactions`, `confia_fiscal_sequence_gaps` | Alerta S1 y runbook de descuadre. La cuenta afectada se marca `UNDER_REVIEW` y se bloquean sus escrituras |
        | **Generación de cargos** | Diaria; genera los cargos de cada plan de cobro en su día de devengo | Genera los cargos del período, con un cargo por estudiante, concepto y período garantizado por restricción única | `confia_charges_generated_total`, `confia_charge_generation_last_success_timestamp_seconds` | Alerta S2 `ChargeGenerationMissed`. Reejecutar es seguro: la tarea es idempotente |
        | **Purga de tokens de refresco vencidos** | Diaria | Elimina los tokens de refresco vencidos o revocados (ADR-0005) | Publica el conteo purgado como métrica | Alerta S3 `TaskFailed`. Un token vencido se rechaza igual al validarse; la purga reduce volumen y superficie |
        | **Verificación de la cadena de auditoría, horaria** | Cada hora | Compara el último `record_hash` contra el ancla publicada en almacenamiento de objetos y publica el ancla nueva | `confia_audit_chain_broken` | Alerta S1 y runbook de incidente de seguridad. No se repara la cadena: se preserva la evidencia |
        | **Verificación de la cadena de auditoría, diaria** | Diaria, 03:00 | Recalcula la cadena completa del último mes | `confia_audit_chain_verified_rows` | Igual |
        | **Verificación de la cadena de auditoría, semanal** | Domingo, 03:30 | Recalcula desde el registro génesis | `confia_audit_chain_verified_rows{scope="weekly"}` | Igual |
        | **Respaldo lógico fuera de sitio** | Diaria, 01:00 | `infra/scripts/backup.sh nightly`: `pg_dump` contra RDS, cifrado con age, subido a un proveedor de almacenamiento distinto al de cómputo, sin retención local (ADR-0014) | `confia_backup_last_success_timestamp_seconds`, `confia_backup_duration_seconds` | Alerta S1 `BackupMissing`. Es el único punto de recuperación ante la pérdida total de la cuenta de AWS |
        | **Respaldo automático de RDS** | Continuo, gestionado por AWS | Respaldo automático con restauración a un punto en el tiempo, sin script propio (ADR-0014) | `confia_rds_backup_enabled`, `confia_rds_restorable_time_lag_seconds` | Alertas S1 `RdsBackupDisabled` y `RdsRestorableTimeLagHigh`. El objetivo de punto de recuperación de 15 minutos dentro de AWS depende de esto |
        | **Prueba de restauración de respaldo** | Mensual, primer sábado | Restaura el respaldo más reciente en un entorno aislado, cuenta registros, verifica el cuadre del libro mayor y verifica la cadena de auditoría. Deja acta con fecha, duración y resultado | `confia_restore_drill_last_success_timestamp_seconds`, `confia_restore_drill_duration_seconds` | Alerta S3 `RestoreDrillOverdue`. Un respaldo no restaurado no cuenta como respaldo. Ver `docs/runbooks/restauracion-de-respaldo.md` |
        | **Revisión de rangos CAI** | Diaria, 07:00, y tras cada emisión | Recalcula porcentaje consumido, correlativos restantes y días hasta la fecha límite por punto de emisión | `confia_cai_range_consumed_percent`, `confia_cai_range_remaining_numbers`, `confia_cai_range_days_to_expiry` | Alertas escalonadas en 85, 95 y 100 por ciento. Ver `docs/runbooks/rango-cai-agotado.md` |
        | **Limpieza por retención de datos** | Semanal, domingo 04:00 | Aplica la política de `docs/08-datos-privacidad-y-retencion.md`: elimina o anonimiza lo vencido, respetando siempre las obligaciones de conservación fiscal | `confia_data_retention_pending_records` | Alerta S3 `RetentionBacklog`. Cada eliminación o anonimización se audita |
        | **Reconstrucción de la caché de saldos** | Diaria, 02:30, después de la verificación de integridad | Reconstruye desde cero el saldo materializado y lo compara contra el existente antes de sustituirlo | `confia_ledger_cached_balance_drift_accounts` | Alerta S2. **Nunca se sobrescribe la caché sin registrar primero la divergencia detectada** |
        | **Verificación de MFA en roles financieros** | Diaria, 06:00 | Comprueba que ningún usuario con permiso de escritura financiera tiene MFA inactiva | Publica el conteo como métrica | Alerta S2 `MfaMissingOnFinancialRole` y suspensión de sesión |
        | **Recálculo de cartera por antigüedad** | Diaria, 05:00 | Recalcula la cartera vencida por tramo desde el libro mayor | `confia_outstanding_balance_minor_units` | Alerta S3. Es informativo, no bloqueante |
        | **Verificación de certificados y secretos** | Diaria, 08:00 | Días restantes de certificados TLS y de secretos con rotación programada | `confia_tls_certificate_expiry_timestamp_seconds` | Alerta S3 `CertificateExpiringSoon` |
        | **Escaneo de vulnerabilidades de la imagen en ejecución** | Semanal, lunes 06:00 | Trivy contra el digest desplegado, no contra la etiqueta | Publica conteo por severidad | Alerta S3, o S2 si hay vulnerabilidad crítica con explotación conocida |
        | **Reporte de segregación de funciones** | Trimestral | Genera el reporte "quién puede hacer qué" y lo deja para revisión con el propietario | - | Alerta S3 si no se generó |
        """;

    private static final String ORIGINAL_DOCS_07_SECTION_11_1 = """
        ```bash
        # Estado general del despliegue
        docker compose -f infra/docker/compose.prod.yml ps

        # Logs correlacionados de una solicitud concreta, en los tres servicios
        docker compose logs --since 24h --no-log-prefix api-admin api-portal worker \\
          | grep '"requestId":"018f3a2b-9c4d-7e10-8f21-9a7b3c5d1e00"'

        # Manual ledger integrity check: triggered from the admin API through a use case guarded by a
        # permission and audited, which schedules the same one-time task used by the nightly run
        # (ADR-0016). Nobody writes to the task table by hand. Endpoint and permission are defined in the
        # ledger module specification.

        # Background task status, read only. Default db-scheduler columns, validated in F0 (ADR-0016).
        # The task data column is never selected.
        docker compose exec postgres psql -U confia_owner -d confia -c "
          SELECT task_name,
                 count(*) FILTER (WHERE NOT picked AND execution_time <= now())             AS due,
                 count(*) FILTER (WHERE picked)                                             AS running,
                 count(*) FILTER (WHERE consecutive_failures > 0)                           AS failing,
                 min(execution_time) FILTER (WHERE NOT picked AND execution_time <= now())  AS oldest_due
          FROM scheduled_tasks
          GROUP BY task_name
          ORDER BY task_name;"

        # Estado actual de los rangos CAI
        docker compose exec postgres psql -U confia_owner -d confia -c "
          SELECT issuance_point_id, document_type, cai, range_from, range_to, current_number,
                 ROUND(100.0 * (current_number - range_from) / NULLIF(range_to - range_from, 0), 2)
                   AS consumed_percent,
                 valid_until, status
          FROM cai_range
          WHERE status = 'active'
          ORDER BY consumed_percent DESC;"
        ```
        """;

    private static final String ORIGINAL_DOCS_05_NOTE_2026_10_04 = """
        > **Nota fechada 2026-10-04 (cambio `web-edge-foundations`, PR 2 `identity-beans`): variables del
        > proceso administrativo.** Desde este cambio, solo el proceso `admin` abre una conexión a la base de
        > datos y registra los casos de uso de identidad. Necesita, además de `SPRING_DATASOURCE_URL`,
        > `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD` (el rol `confia_admin_app`), tres
        > secretos. Se leen al arrancar y, si falta alguno o es inválido, el proceso se detiene antes de
        > aceptar una sola petición, con un mensaje que nombra la propiedad y nunca repite ninguna parte
        > del valor:
        >
        > | Variable de entorno | Propiedad | Formato |
        > |---|---|---|
        > | `CONFIA_CRYPTO_COLUMNMASTERKEY` | `confia.crypto.column-master-key` | Base64 de 32 bytes: llave maestra del cifrado de columnas |
        > | `CONFIA_IDENTITY_ARGON2PEPPER` | `confia.identity.argon2-pepper` | Base64 de 32 bytes: pimienta de Argon2id y del identificador de inicio de sesión |
        > | `CONFIA_IDENTITY_LOGININSTITUTIONID` | `confia.identity.login-institution-id` | UUID de la institución de inicio de sesión del proceso |
        >
        > Los tres valores viven en el almacén de secretos y nunca en el repositorio, ni siquiera como
        > ejemplo. `portal` y `worker` no los leen ni abren conexión a la base de datos. El grupo de
        > conexiones es perezoso: un proceso `admin` con la base de datos caída arranca y reporta el fallo
        > en el primer uso. Los casos de uso `RequestPasswordReset` e `IssuePasswordResetToken` no se
        > registran hasta que existan sus adaptadores (cambios 9 y 14).
        """;

    private static final String ORIGINAL_EXPLORATION_CONDITION_4 = """

        **Condición dura de aceptación de `background-jobs-with-db-scheduler` (cambio 9) (2026-10-04, de
        `web-edge-foundations`, revisión de seguridad del PR 6b).** La protección de registros de la regla 11
        (`SensitiveLogGuard` y `Settings.withExecuteLogging(false)` de jOOQ) existe hoy solo donde hay `DataSource`
        y borde web: el proceso administrativo. El trabajador no tiene `DataSource` y su política de procesos
        prohíbe `com.confia.shared.web`, de modo que no recibe la guardia. Se escribe aquí y no como pregunta abierta,
        porque una pregunta abierta no bloquea nada:

        > **4. `background-jobs-with-db-scheduler` NO DEBE dar un `DataSource` al trabajador ni ejecutar SQL en él sin que
        > la protección de registros sensibles esté activa en ese proceso, y NO DEBE fusionarse sin una prueba que lo
        > demuestre** (con `org.jooq` y la raíz en `DEBUG`, un valor enlazado distintivo no aparece en ningún evento del
        > proceso trabajador real).

        Como el trabajador prohíbe `com.confia.shared.web`, la protección debe llegar por una configuración que el
        """;

    private static final String ORIGINAL_DOCS_03_SECTION_5_1 = """
        ### 5.1 Modelo RBAC con permisos granulares

        La autorización se decide **siempre en el servidor**. El frontend oculta lo que el usuario no
        puede hacer solo por experiencia de uso, nunca como control.

        Estructura:

        ```
        Usuario  ->  Rol (uno o varios)  ->  Permiso (módulo + verbo)
        ```

        Un permiso se nombra `modulo:verbo`, por ejemplo `payments:create` o `invoicing:void`. Los verbos
        son los seis siguientes y ninguno más:

        | Verbo | Significado | Nota |
        |---|---|---|
        | `create` | Crear un registro nuevo | |
        | `read` | Consultar | |
        | `update` | Modificar datos no financieros | Nunca aplica a asientos del libro mayor |
        | `void` (anular) | Anular o reversar una operación | Siempre genera asiento de reverso, nunca borra |
        | `approve` (aprobar) | Autorizar una operación que otro actor inició | Base de la segregación de funciones |
        | `export` (exportar) | Extraer datos a CSV, Excel o PDF en volumen | Separado de `read` a propósito, por riesgo de fuga masiva |

        Los permisos se declaran en el controlador con una anotación y Spring Security los verifica en
        el servidor. **Un controlador sin permiso declarado no arranca la aplicación**: el arranque
        recorre el mapa de rutas y falla si encuentra una ruta sin metadato de permiso o sin marca
        explícita de pública.

        **Cómo se comprueba.** Prueba de arranque que enumera todas las rutas de OpenAPI y verifica que
        cada una declara permiso o es explícitamente pública. La lista de rutas públicas es una lista
        blanca corta y revisada.
        """;

    private static final String ORIGINAL_DOCS_03_SECTION_5_2 = """
        ### 5.2 Matriz de permisos por rol

        Leyenda: **C** crear, **L** leer, **A** actualizar, **N** anular, **P** aprobar, **X** exportar.
        Una celda vacía significa sin acceso. `L*` significa lectura restringida por seguridad a nivel de
        fila a los propios estudiantes vinculados.

        | Módulo | Super Admin | Administrador | Cajero | Contabilidad | Auditor | Coord. Académico | Encargado |
        |---|---|---|---|---|---|---|---|
        | `identity` (usuarios, roles) | C L A N P X | L | | | L | | |
        | `identity` (MFA propia) | C L A | C L A | C L A | C L A | C L A | C L A | C L A |
        | `organization` (institución, año, grado) | C L A N X | C L A X | L | L | L X | C L A X | |
        | `students` | C L A N X | C L A X | L | L | L X | C L A X | L* |
        | `guardians` | C L A N X | C L A X | L | L | L X | L | L* A* |
        | `catalog` (conceptos y tarifas) | C L A N X | C L A X | L | L X | L X | L | |
        | `scholarships` (becas) | C L A N P X | C L A P X | L | L X | L X | C L | L* |
        | `charges` (devengo y cargos) | C L A N P X | C L N P X | L | L X | L X | L | L* |
        | `ledger` (libro mayor) | L N P X | L N X | L | L X | L X | | L* |
        | `payments` | C L N P X | C L N P X | **C L** | L N X | L X | | L* |
        | `cashbox` (sesiones de caja) | C L N P X | L P X | **C L** | L X | L X | | |
        | `invoicing` (factura, CAI, NC) | C L N P X | L P X | **C L** | **C L N P X** | L X | | L* |
        | `collections` (mora y cobranza) | C L A N P X | C L A P X | L | L X | L X | L | L* |
        | `reconciliation` (bancaria) | C L A P X | L P X | | **C L A P X** | L X | | |
        | `documents` (constancias) | C L A N P X | C L A P X | L | | L X | C L A P X | **C** L* |
        | `reporting` | L X | L X | L (solo su caja) | L X | **L X** | L X (académico) | |
        | `notifications` | C L A N X | C L A X | L | | L X | C L | L* A* (preferencias) |
        | `audit` (bitácora) | **L X** | L | | | **L X** | | |
        | `settings` (configuración) | C L A N X | L A | | L | L X | | |

        Reglas de la matriz que no se ven en la tabla:

        1. **Nadie tiene `update` sobre `ledger`.** Ni el Super Administrador. La tabla de asientos no
           acepta `UPDATE` a nivel de motor de base de datos.
        2. **Nadie tiene `delete`.** El verbo no existe en el sistema. La anulación es siempre `void`.
        3. **El Auditor es de solo lectura absoluta.** Es el único rol con lectura completa de la bitácora
           y sin ninguna capacidad de escritura en todo el sistema. Su cuenta existe para responder a un
           auditor externo sin concederle privilegio operativo.
        4. **El Cajero no puede anular.** Puede crear pagos y facturas dentro de su sesión de caja, pero
           la anulación requiere `invoicing:void`, que reside en Contabilidad y Super Administrador. Este
           es el control central contra el actor 2.
        5. **El Administrador no puede cobrar.** No tiene `payments:create` ni `cashbox:create`. Quien
           configura el sistema no maneja el efectivo.
        6. **`export` sobre `students` y `guardians`** se audita siempre con el filtro y el conteo de
           filas, y dispara alerta sobre un umbral configurable.
        7. **El Encargado nunca tiene permisos administrativos.** Su privilegio se define además por el
           proceso: el binario del portal no contiene los módulos administrativos.

        La matriz vive también como dato en la migración semilla, de modo que el documento y el sistema no
        puedan divergir en silencio.

        **Cómo se comprueba.** Una prueba de integración lee esta matriz desde un archivo de definición
        compartido y verifica, rol por rol y endpoint por endpoint, que el acceso concedido y el denegado
        coinciden con la tabla. Un cambio de permiso que no actualice la definición rompe la construcción.
        Trimestralmente se genera el reporte "quién puede hacer qué" y se revisa con el propietario.
        """;

    private static final String ORIGINAL_DOCS_03_SECTION_5_3 = """
        ### 5.3 Segregación de funciones

        Ninguna persona debe poder completar sola un ciclo que mueva dinero fuera del control interno.

        | Operación sensible | Quien inicia | Quien aprueba | Regla |
        |---|---|---|---|
        | Anulación de factura | Cajero o Contabilidad | Contabilidad o Super Administrador, **actor distinto del que emitió** | El sistema rechaza la aprobación si el aprobador es el emisor |
        | Nota de crédito sobre monto mayor al umbral | Contabilidad | Super Administrador | Umbral configurable, con vigencia |
        | Ajuste manual en el libro mayor | Administrador | Super Administrador | Motivo obligatorio de al menos 20 caracteres, adjunto opcional |
        | Baja por incobrable | Contabilidad | Super Administrador | Siempre requiere aprobación, sin umbral |
        | Cierre de caja con diferencia superior al umbral | Cajero | Administrador o Super Administrador | Ver `docs/runbooks/cierre-de-caja-con-diferencia.md` |
        | Alta o cambio de rol de usuario | Super Administrador | No autoaplicable | Un usuario nunca puede modificar sus propios roles |
        | Cambio de configuración fiscal (CAI, tasas) | Contabilidad | Super Administrador | Toda la configuración fiscal tiene vigencia y auditoría |
        | Reembolso a un tercero | Contabilidad | Super Administrador | Con documento fiscal correspondiente |

        Implementación: las operaciones marcadas crean un registro en estado `pendiente de aprobación` con
        su actor iniciador. La aprobación es un caso de uso separado que valida que el aprobador tiene el
        permiso `approve` del módulo y que **su identificador difiere del iniciador**. Ambos eventos se
        auditan. Una aprobación caduca a las 72 horas sin resolución y notifica.

        **Cómo se comprueba.** Prueba de integración por cada fila: el mismo actor intenta iniciar y
        aprobar, y el sistema rechaza con 403 y un tipo de error específico. El reporte trimestral de
        segregación de funciones no debe listar ningún usuario con ambos permisos del mismo par.
        """;
}
