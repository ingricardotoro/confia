package com.confia.imports.application;

import com.confia.guardians.application.importing.GuardianImportGateway;
import com.confia.guardians.application.importing.GuardianLinkImportGateway;
import com.confia.imports.domain.ImportBatch;
import com.confia.imports.domain.ImportFileChangedException;
import com.confia.imports.domain.ImportKind;
import com.confia.imports.domain.ImportNotSimulatedException;
import com.confia.imports.domain.ImportStatus;
import com.confia.imports.domain.InvalidImportFileException;
import com.confia.imports.domain.Rejection;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.Digests;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.importing.EnrollmentImportGateway;
import com.confia.students.application.importing.StudentImportGateway;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Simulates and applies the bulk import of the students, the guardians and the links between them of an institution
 * from a CSV file, and lists the batches (docs/cortes/imports-students.md, docs/cortes/imports-guardians.md).
 *
 * <p>A <b>simulation</b> reads the file, has the module that owns the data judge every row with the rules of its
 * registry and records a {@code VALIDATED} batch (counts and the SHA-256 of the file); it writes nothing. An
 * <b>application</b> only proceeds when the account simulated a file of the same kind with the same fingerprint, reads
 * and judges the file again, has the owning module register the valid rows through the same use case as a hand
 * registration and records an {@code APPLIED} batch. A reference from the source system makes it idempotent: a row
 * imported before is skipped, and two applications of the same file at once create each record once.
 *
 * <p>The file, its rows and the rejected values are never stored, logged or audited: they live in memory for the length
 * of the call. The rejections go to the caller as a line, a column and a reason. The batch and its audit entry carry
 * counts and the fingerprint only. What differs per kind (the template of the file and the port that judges and
 * registers the rows) is an {@link ImportProcessor}; everything else is shared.
 */
public final class ManageImports {

    /** The largest file accepted, in bytes (also limited at the web edge). */
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    /** The most data rows a file may have. */
    public static final int MAX_ROWS = 10_000;
    /** The most rejections an answer lists. */
    public static final int MAX_REPORTED = 200;

    private static final String FILE = "file";
    private static final char BYTE_ORDER_MARK = '﻿';

    private final TransactionRunner transactionRunner;
    private final ImportBatchRepository batches;
    private final Map<ImportKind, ImportProcessor> processors = new EnumMap<>(ImportKind.class);
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageImports(TransactionRunner transactionRunner, ImportBatchRepository batches,
            StudentImportGateway students, GuardianImportGateway guardians, GuardianLinkImportGateway links,
            EnrollmentImportGateway enrollments, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.batches = Objects.requireNonNull(batches, "batches");
        for (ImportProcessor processor : List.of(new StudentsProcessor(students), new GuardiansProcessor(guardians),
                new GuardianLinksProcessor(links), new EnrollmentsProcessor(enrollments))) {
            processors.put(processor.kind(), processor);
        }
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What reading and judging a file found. */
    private record Reading(String sha256, int totalRows, int validRows, List<Rejection> rejections, int rejectedRows,
            int skippedRows, ImportProcessor.Registrar registrar) {
    }

    public ImportRun simulate(ImportKind kind, AuthenticatedActor actor, String requestId, byte[] file) {
        try {
            return doSimulate(kind, actor, requestId, file);
        } catch (InvalidImportFileException e) {
            throw rejected(e);
        }
    }

    public ImportRun apply(ImportKind kind, AuthenticatedActor actor, String requestId, byte[] file) {
        try {
            return doApply(kind, actor, requestId, file);
        } catch (InvalidImportFileException e) {
            throw rejected(e);
        }
    }

    /** The simulation of the kind with this name in the routes. */
    public ImportRun simulate(String route, AuthenticatedActor actor, String requestId, byte[] file) {
        return simulate(kindOf(route), actor, requestId, file);
    }

    /** The application of the kind with this name in the routes. */
    public ImportRun apply(String route, AuthenticatedActor actor, String requestId, byte[] file) {
        return apply(kindOf(route), actor, requestId, file);
    }

    /**
     * One page of the history. {@code route} filters by kind (null for every kind the account may read); the kinds of
     * guardians need {@code canReadGuardians} and the students one {@code canReadStudents}.
     *
     * @throws UnknownImportKindException the name is not a kind
     * @throws KindNotReadableException the kind asked for cannot be read by the account
     */
    public Page<ImportBatchView> list(AuthenticatedActor actor, String requestId, int page, int size, String route,
            boolean canReadStudents, boolean canReadGuardians) {
        Set<ImportKind> readable = java.util.EnumSet.noneOf(ImportKind.class);
        if (canReadStudents) {
            readable.add(ImportKind.STUDENTS);
            readable.add(ImportKind.ENROLLMENTS);
        }
        if (canReadGuardians) {
            readable.add(ImportKind.GUARDIANS);
            readable.add(ImportKind.GUARDIAN_LINKS);
        }
        if (route == null) {
            return list(actor, requestId, page, size, readable);
        }
        ImportKind kind = kindOf(route);
        if (!readable.contains(kind)) {
            throw new KindNotReadableException(kind == ImportKind.STUDENTS || kind == ImportKind.ENROLLMENTS);
        }
        return list(actor, requestId, page, size, Set.of(kind));
    }

    private static ImportKind kindOf(String route) {
        ImportKind kind = ImportKind.ofRoute(route);
        if (kind == null) {
            throw new UnknownImportKindException();
        }
        return kind;
    }

    private static RejectedImportFileException rejected(InvalidImportFileException e) {
        return new RejectedImportFileException(e.problems().stream()
                .map(p -> new RejectedImportFileException.FileProblem(p.field(), p.reason())).toList());
    }

    private ImportRun doSimulate(ImportKind kind, AuthenticatedActor actor, String requestId, byte[] file) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ImportsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        Reading reading = read(kind, actor, auditRequestId, file);
        ImportBatch batch = ImportBatch.simulated(UUID.randomUUID(), kind, reading.sha256(), reading.totalRows(),
                reading.validRows(), reading.rejectedRows(), reading.skippedRows(), actor.accountId(), now());
        record(actor, auditRequestId, institution, batch, false);
        return report(batch, reading.rejections());
    }

    private ImportRun doApply(ImportKind kind, AuthenticatedActor actor, String requestId, byte[] file) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ImportsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        checkSize(file);
        String sha256 = HexFormat.of().formatHex(Digests.sha256(file));
        UUID simulationId = simulationOf(kind, actor, auditRequestId, institution, sha256);

        Reading reading = read(kind, actor, auditRequestId, file);
        ImportProcessor.Registration registration;
        try {
            registration = reading.registrar().register();
        } catch (PartialRegistrationException partial) {
            throw failedAfterCommitting(kind, actor, auditRequestId, sha256, reading.totalRows(), partial.created(),
                    partial.failure());
        }
        Set<Integer> lateSkipped = new HashSet<>();
        Set<Integer> lateRejected = new HashSet<>();
        for (Rejection late : registration.late()) {
            (late.isSkipped() ? lateSkipped : lateRejected).add(late.line());
        }
        List<Rejection> rejections = new ArrayList<>(reading.rejections());
        rejections.addAll(registration.late());
        ImportBatch batch = ImportBatch.applied(UUID.randomUUID(), kind, sha256, reading.totalRows(),
                registration.created(), reading.rejectedRows() + lateRejected.size(),
                reading.skippedRows() + lateSkipped.size(), simulationId, actor.accountId(), now());
        try {
            record(actor, auditRequestId, institution, batch, true);
        } catch (RuntimeException failure) {
            throw failedAfterCommitting(kind, actor, auditRequestId, sha256, reading.totalRows(),
                    registration.created(), failure);
        }
        return report(batch, rejections);
    }

    /**
     * The application stopped after some rows were committed, which stay: a trace with the counts and the fingerprint
     * is left in the audit trail (in a transaction of its own, because the one of the failure is gone) and the
     * original failure is the one that propagates. If the trace cannot be written either, that is added to the
     * original failure as suppressed and does not replace it.
     */
    private RuntimeException failedAfterCommitting(ImportKind kind, AuthenticatedActor actor, UUID auditRequestId,
            String sha256, int totalRows, int created, RuntimeException failure) {
        if (created > 0) {
            try {
                transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
                    auditLogWriter.append(ImportsAudit.failed(actor, auditRequestId, UUID.randomUUID(), kind, sha256,
                            totalRows, created));
                    return null;
                });
            } catch (RuntimeException traceFailure) {
                failure.addSuppressed(traceFailure);
            }
        }
        return failure;
    }

    /**
     * One page of the batches of the institution, newest first, of the given kinds only (the caller decides which
     * kinds the account may see).
     */
    public Page<ImportBatchView> list(AuthenticatedActor actor, String requestId, int page, int size,
            Set<ImportKind> kinds) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(kinds, "kinds");
        UUID auditRequestId = ImportsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        PageRequest request = new PageRequest(page, size);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> batches.list(institution, request, kinds).map(ImportBatchView::of));
    }

    /**
     * The simulation of this fingerprint by this account for this kind. Without one the answer depends on what the
     * account did last with this kind: if its latest batch is a simulation, the file is not the one it simulated
     * ({@code import-file-changed}); otherwise it never simulated anything that this file could be ({@code
     * import-not-simulated}). A simulation of another kind never counts.
     */
    private UUID simulationOf(ImportKind kind, AuthenticatedActor actor, UUID auditRequestId,
            InstitutionId institution, String sha256) {
        Optional<ImportBatch> found = transactionRunner.executeReadOnly(
                actor.securityContext(auditRequestId.toString()), () -> {
                    Optional<ImportBatch> simulation =
                            batches.findValidated(institution, actor.accountId(), kind, sha256);
                    if (simulation.isPresent()) {
                        return simulation;
                    }
                    Optional<ImportBatch> latest = batches.latestOf(institution, actor.accountId(), kind);
                    if (latest.isPresent() && latest.get().status() == ImportStatus.VALIDATED) {
                        throw new ImportFileChangedException();
                    }
                    throw new ImportNotSimulatedException();
                });
        return found.orElseThrow().id();
    }

    private static void checkSize(byte[] file) {
        if (file == null || file.length == 0) {
            throw new InvalidImportFileException(FILE, "empty-file");
        }
        if (file.length > MAX_BYTES) {
            throw new InvalidImportFileException(FILE, "too-large");
        }
    }

    /** Decodes, parses and has the owning module judge the file; writes nothing. */
    private Reading read(ImportKind kind, AuthenticatedActor actor, UUID auditRequestId, byte[] file) {
        checkSize(file);
        String sha256 = HexFormat.of().formatHex(Digests.sha256(file));
        ImportProcessor.Judgement judgement =
                processors.get(kind).judge(actor, auditRequestId.toString(), decode(file), MAX_ROWS);

        List<Rejection> defects = new ArrayList<>();
        List<Rejection> skipped = new ArrayList<>();
        Set<Integer> defectLines = new TreeSet<>();
        Set<Integer> problemLines = new TreeSet<>();
        for (Rejection rejection : judgement.rejections()) {
            problemLines.add(rejection.line());
            if (rejection.isSkipped()) {
                skipped.add(rejection);
            } else {
                defects.add(rejection);
                defectLines.add(rejection.line());
            }
        }
        Set<Integer> skippedLines = new TreeSet<>();
        skipped.forEach(rejection -> skippedLines.add(rejection.line()));
        skippedLines.removeAll(defectLines);

        List<Rejection> rejections = new ArrayList<>(defects);
        rejections.sort(Comparator.comparingInt(Rejection::line));
        skipped.sort(Comparator.comparingInt(Rejection::line));
        rejections.addAll(skipped);
        return new Reading(sha256, judgement.totalRows(), judgement.totalRows() - problemLines.size(), rejections,
                defectLines.size(), skippedLines.size(), judgement.registrar());
    }

    /** UTF-8 only, no replacement characters, no NUL, and the byte order mark of a spreadsheet dropped. */
    private static String decode(byte[] file) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file)).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidImportFileException(FILE, "invalid-encoding");
        }
        if (text.indexOf('\0') >= 0) {
            throw new InvalidImportFileException(FILE, "invalid-encoding");
        }
        return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
    }

    /** Inserts the batch and its audit entry in one transaction. */
    private void record(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution, ImportBatch batch,
            boolean applied) {
        transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            batches.insert(institution, batch);
            auditLogWriter.append(applied ? ImportsAudit.applied(actor, auditRequestId, batch)
                    : ImportsAudit.simulated(actor, auditRequestId, batch));
            return null;
        });
    }

    /** Defects first and then the skipped rows, each in line order, at most {@link #MAX_REPORTED}. */
    private static ImportRun report(ImportBatch batch, List<Rejection> rejections) {
        List<Rejection> ordered = new ArrayList<>(rejections.stream().filter(r -> !r.isSkipped())
                .sorted(Comparator.comparingInt(Rejection::line)).toList());
        ordered.addAll(rejections.stream().filter(Rejection::isSkipped)
                .sorted(Comparator.comparingInt(Rejection::line)).toList());
        List<RejectionView> shown = ordered.stream().limit(MAX_REPORTED)
                .map(r -> new RejectionView(r.line(), r.field(), r.reason())).toList();
        return new ImportRun(ImportBatchView.of(batch), shown, ordered.size() > MAX_REPORTED);
    }

    /** The instant of the write, to the microsecond the column keeps, so an answer equals what a read returns. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
