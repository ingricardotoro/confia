package com.confia.imports.application;

import com.confia.imports.domain.ImportBatch;
import com.confia.imports.domain.ImportFileChangedException;
import com.confia.imports.domain.ImportKind;
import com.confia.imports.domain.ImportNotSimulatedException;
import com.confia.imports.domain.ImportStatus;
import com.confia.imports.domain.InvalidImportFileException;
import com.confia.imports.domain.Rejection;
import com.confia.imports.domain.StudentsCsv;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.Digests;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.importing.CreateOutcome;
import com.confia.students.application.importing.ImportRow;
import com.confia.students.application.importing.PartialImportException;
import com.confia.students.application.importing.RowProblem;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Simulates and applies the import of the students of an institution from a CSV file, and lists the batches
 * (docs/cortes/imports-students.md).
 *
 * <p>A <b>simulation</b> reads the file, has {@code students} judge every row with the rules of the registry and
 * records a {@code VALIDATED} batch (counts and the SHA-256 of the file); it writes no student. An <b>application</b>
 * only proceeds when the account simulated a file with the same fingerprint, reads and judges the file again, has
 * {@code students} register the valid rows through the same use case as a hand registration and records an {@code
 * APPLIED} batch. A reference from the source system on each student makes it idempotent: a row imported before is
 * skipped, and two applications of the same file at once create each student once.
 *
 * <p>The file, its rows and the rejected values are never stored, logged or audited: they live in memory for the length
 * of the call. The rejections go to the caller as a line, a column and a reason. The batch and its audit entry carry
 * counts and the fingerprint only.
 */
public final class ImportStudents {

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
    private final StudentImportGateway students;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ImportStudents(TransactionRunner transactionRunner, ImportBatchRepository batches,
            StudentImportGateway students, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.batches = Objects.requireNonNull(batches, "batches");
        this.students = Objects.requireNonNull(students, "students");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What reading and judging a file found. */
    private record Reading(String sha256, int totalRows, List<ImportRow> validRows, List<Rejection> rejections,
            int rejectedRows, int skippedRows) {
    }

    public ImportRun simulate(AuthenticatedActor actor, String requestId, byte[] file) {
        try {
            return doSimulate(actor, requestId, file);
        } catch (InvalidImportFileException e) {
            throw rejected(e);
        }
    }

    public ImportRun apply(AuthenticatedActor actor, String requestId, byte[] file) {
        try {
            return doApply(actor, requestId, file);
        } catch (InvalidImportFileException e) {
            throw rejected(e);
        }
    }

    private static RejectedImportFileException rejected(InvalidImportFileException e) {
        return new RejectedImportFileException(e.problems().stream()
                .map(p -> new RejectedImportFileException.FileProblem(p.field(), p.reason())).toList());
    }

    private ImportRun doSimulate(AuthenticatedActor actor, String requestId, byte[] file) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ImportsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        Reading reading = read(actor, auditRequestId, file);
        ImportBatch batch = ImportBatch.simulated(UUID.randomUUID(), ImportKind.STUDENTS, reading.sha256(),
                reading.totalRows(), reading.validRows().size(), reading.rejectedRows(), reading.skippedRows(),
                actor.accountId(), now());
        record(actor, auditRequestId, institution, batch, false);
        return report(batch, reading.rejections());
    }

    private ImportRun doApply(AuthenticatedActor actor, String requestId, byte[] file) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ImportsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        if (file == null || file.length == 0) {
            throw new InvalidImportFileException(FILE, "empty-file");
        }
        if (file.length > MAX_BYTES) {
            throw new InvalidImportFileException(FILE, "too-large");
        }
        String sha256 = HexFormat.of().formatHex(Digests.sha256(file));
        UUID simulationId = simulationOf(actor, auditRequestId, institution, sha256);

        Reading reading = read(actor, auditRequestId, file);
        List<CreateOutcome> outcomes;
        try {
            outcomes = reading.validRows().isEmpty() ? List.of()
                    : students.createAll(actor, auditRequestId.toString(), reading.validRows());
        } catch (PartialImportException partial) {
            throw failedAfterCommitting(actor, auditRequestId, sha256, reading.totalRows(), partial.created(),
                    partial.failure());
        }
        int created = 0;
        int lateSkipped = 0;
        List<Rejection> rejections = new ArrayList<>(reading.rejections());
        int lateRejected = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            switch (outcomes.get(i)) {
                case CREATED -> created++;
                case ALREADY_IMPORTED -> {
                    lateSkipped++;
                    rejections.add(new Rejection(reading.validRows().get(i).line(), "legacyId",
                            Rejection.ALREADY_IMPORTED));
                }
                case DUPLICATE_NATIONAL_ID -> {
                    lateRejected++;
                    rejections.add(new Rejection(reading.validRows().get(i).line(), "nationalId",
                            "duplicate-national-id"));
                }
            }
        }
        ImportBatch batch = ImportBatch.applied(UUID.randomUUID(), ImportKind.STUDENTS, sha256, reading.totalRows(),
                created, reading.rejectedRows() + lateRejected, reading.skippedRows() + lateSkipped, simulationId,
                actor.accountId(), now());
        try {
            record(actor, auditRequestId, institution, batch, true);
        } catch (RuntimeException failure) {
            throw failedAfterCommitting(actor, auditRequestId, sha256, reading.totalRows(), created, failure);
        }
        return report(batch, rejections);
    }

    /**
     * The application stopped after some students were committed, which stay: a trace with the counts and the
     * fingerprint is left in the audit trail (in a transaction of its own, because the one of the failure is gone) and
     * the original failure is the one that propagates. If the trace cannot be written either, that is added to the
     * original failure as suppressed and does not replace it.
     */
    private RuntimeException failedAfterCommitting(AuthenticatedActor actor, UUID auditRequestId, String sha256,
            int totalRows, int created, RuntimeException failure) {
        if (created > 0) {
            try {
                transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
                    auditLogWriter.append(ImportsAudit.failed(actor, auditRequestId, UUID.randomUUID(),
                            ImportKind.STUDENTS, sha256, totalRows, created));
                    return null;
                });
            } catch (RuntimeException traceFailure) {
                failure.addSuppressed(traceFailure);
            }
        }
        return failure;
    }

    public Page<ImportBatchView> list(AuthenticatedActor actor, String requestId, int page, int size) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ImportsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        PageRequest request = new PageRequest(page, size);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> batches.list(institution, request).map(ImportBatchView::of));
    }

    /**
     * The simulation of this fingerprint by this account. Without one the answer depends on what the account did last:
     * if its latest batch is a simulation, the file is not the one it simulated ({@code import-file-changed});
     * otherwise it never simulated anything that this file could be ({@code import-not-simulated}).
     */
    private UUID simulationOf(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution,
            String sha256) {
        Optional<ImportBatch> found = transactionRunner.executeReadOnly(
                actor.securityContext(auditRequestId.toString()), () -> {
                    Optional<ImportBatch> simulation =
                            batches.findValidated(institution, actor.accountId(), ImportKind.STUDENTS, sha256);
                    if (simulation.isPresent()) {
                        return simulation;
                    }
                    Optional<ImportBatch> latest =
                            batches.latestOf(institution, actor.accountId(), ImportKind.STUDENTS);
                    if (latest.isPresent() && latest.get().status() == ImportStatus.VALIDATED) {
                        throw new ImportFileChangedException();
                    }
                    throw new ImportNotSimulatedException();
                });
        return found.orElseThrow().id();
    }

    /** Decodes, parses and has the registry judge the file; writes nothing. */
    private Reading read(AuthenticatedActor actor, UUID auditRequestId, byte[] file) {
        if (file == null || file.length == 0) {
            throw new InvalidImportFileException(FILE, "empty-file");
        }
        if (file.length > MAX_BYTES) {
            throw new InvalidImportFileException(FILE, "too-large");
        }
        String sha256 = HexFormat.of().formatHex(Digests.sha256(file));
        StudentsCsv.Parsed parsed = StudentsCsv.parse(decode(file), MAX_ROWS);
        List<ImportRow> rows = parsed.rows().stream().map(row -> new ImportRow(row.line(), row.legacyId(),
                row.firstName(), row.lastName(), row.birthDate(), row.sex(), row.nationalId())).toList();
        List<RowProblem> problems = rows.isEmpty() ? List.of()
                : students.evaluate(actor, auditRequestId.toString(), rows);

        List<Rejection> defects = new ArrayList<>(parsed.shapeRejections());
        List<Rejection> skipped = new ArrayList<>();
        Set<Integer> defectLines = new TreeSet<>();
        Set<Integer> problemLines = new TreeSet<>();
        parsed.shapeRejections().forEach(rejection -> {
            defectLines.add(rejection.line());
            problemLines.add(rejection.line());
        });
        for (RowProblem problem : problems) {
            Rejection rejection = new Rejection(problem.line(), problem.field(), problem.reason());
            problemLines.add(problem.line());
            if (rejection.isAlreadyImported()) {
                skipped.add(rejection);
            } else {
                defects.add(rejection);
                defectLines.add(problem.line());
            }
        }
        Set<Integer> skippedLines = new TreeSet<>();
        skipped.forEach(rejection -> skippedLines.add(rejection.line()));
        skippedLines.removeAll(defectLines);
        List<ImportRow> valid = rows.stream().filter(row -> !problemLines.contains(row.line())).toList();

        List<Rejection> rejections = new ArrayList<>(defects);
        rejections.sort(Comparator.comparingInt(Rejection::line));
        skipped.sort(Comparator.comparingInt(Rejection::line));
        rejections.addAll(skipped);
        return new Reading(sha256, parsed.totalRows(), valid, rejections, defectLines.size(), skippedLines.size());
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
        List<Rejection> ordered = new ArrayList<>(rejections.stream().filter(r -> !r.isAlreadyImported())
                .sorted(Comparator.comparingInt(Rejection::line)).toList());
        ordered.addAll(rejections.stream().filter(Rejection::isAlreadyImported)
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
