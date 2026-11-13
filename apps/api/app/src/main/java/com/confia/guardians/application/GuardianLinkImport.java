package com.confia.guardians.application;

import com.confia.guardians.application.importing.GuardianLinkImportGateway;
import com.confia.guardians.application.importing.LinkImportRow;
import com.confia.guardians.application.importing.LinkOutcome;
import com.confia.guardians.application.importing.PartialImportException;
import com.confia.guardians.application.importing.RowProblem;
import com.confia.guardians.domain.Guardian;
import com.confia.guardians.domain.GuardianNotActiveException;
import com.confia.guardians.domain.LinkImportRules;
import com.confia.guardians.domain.StudentNotActiveException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The bulk import of links as {@code guardians} offers it to {@code imports} (docs/cortes/imports-guardians.md). The
 * rows are grouped by student and each group is the complete set that the hand registration demands: it is judged by
 * {@code LinkImportRules}, which applies {@code ResponsibilitySplit} (INV-32) to the set, and it is registered by
 * {@link ManageGuardianLinks#registerImported}, the replacement of the set that locks the row of the student, keeps the
 * history, audits and leaves the last word to the deferred constraint of the database, except that it never replaces
 * links that exist. The references of the source system are resolved against the students (through the directory that
 * {@code students} publishes) and the guardians of the institution; nothing here reads a document or returns a cell.
 *
 * <p>The sets are registered one student per transaction, so a failure of one student never touches the others; a
 * failure that is not a refusal of the set, after some were committed, is a {@link PartialImportException}.
 */
public final class GuardianLinkImport implements GuardianLinkImportGateway {

    static final int LOOKUP_CHUNK = 1000;
    private static final String LOST_REFERENCE = "a row that was judged lost its reference before it was applied";

    private final TransactionRunner transactionRunner;
    private final GuardianRepository guardians;
    private final GuardianLinkRepository links;
    private final StudentDirectory students;
    private final ManageGuardianLinks manageLinks;

    public GuardianLinkImport(TransactionRunner transactionRunner, GuardianRepository guardians,
            GuardianLinkRepository links, StudentDirectory students, ManageGuardianLinks manageLinks) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.links = Objects.requireNonNull(links, "links");
        this.students = Objects.requireNonNull(students, "students");
        this.manageLinks = Objects.requireNonNull(manageLinks, "manageLinks");
    }

    /** What the registry says about the references of a set of rows. */
    private record Facts(Map<String, LinkImportRules.StudentFacts> students,
            Map<String, LinkImportRules.GuardianFacts> guardians) {
    }

    @Override
    public List<RowProblem> evaluate(AuthenticatedActor actor, String requestId, List<LinkImportRow> rows) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(rows, "rows");
        UUID auditRequestId = GuardiansAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        List<LinkImportRules.Row> mapped = rows.stream().map(GuardianLinkImport::rowOf).toList();
        Facts facts = transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> factsOf(institution, mapped));
        return LinkImportRules.judge(mapped, facts.students(), facts.guardians()).stream()
                .map(problem -> new RowProblem(problem.line(), problem.field(), problem.reason())).toList();
    }

    @Override
    public List<LinkOutcome> applyAll(AuthenticatedActor actor, String requestId, List<LinkImportRow> rows) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(rows, "rows");
        UUID auditRequestId = GuardiansAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        List<LinkImportRules.Row> mapped = rows.stream().map(GuardianLinkImport::rowOf).toList();
        Facts facts = transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> factsOf(institution, mapped));

        Map<String, LinkOutcome> outcomes = new HashMap<>();
        int created = 0;
        try {
            for (Map.Entry<String, List<LinkImportRules.Row>> group : LinkImportRules.groupsOf(mapped).entrySet()) {
                LinkOutcome outcome = applyGroup(actor, auditRequestId.toString(), facts, group);
                outcomes.put(group.getKey(), outcome);
                if (outcome == LinkOutcome.CREATED) {
                    created += group.getValue().size();
                }
            }
        } catch (RuntimeException failure) {
            // The sets already committed stay: the caller is told how many rows, to leave a trace of the partial result.
            if (created > 0) {
                throw new PartialImportException(created, failure);
            }
            throw failure;
        }
        return mapped.stream().map(row -> outcomes.get(row.studentLegacyId().strip())).toList();
    }

    private LinkOutcome applyGroup(AuthenticatedActor actor, String requestId, Facts facts,
            Map.Entry<String, List<LinkImportRules.Row>> group) {
        LinkImportRules.StudentFacts student = facts.students().get(group.getKey());
        if (student == null) {
            throw new IllegalStateException(LOST_REFERENCE);
        }
        UUID studentId = student.studentId();
        List<LinkRequest> requested = new ArrayList<>();
        for (LinkImportRules.Row row : group.getValue()) {
            LinkImportRules.GuardianFacts guardian = facts.guardians().get(row.guardianLegacyId().strip());
            if (guardian == null) {
                throw new IllegalStateException(LOST_REFERENCE);
            }
            requested.add(new LinkRequest(guardian.guardianId(),
                    LinkImportRules.relationshipOf(row), LinkImportRules.percentOf(row),
                    LinkImportRules.primaryPayerOf(row), LinkImportRules.billingRecipientOf(row)));
        }
        try {
            return manageLinks.registerImported(actor, requestId, studentId, requested) ? LinkOutcome.CREATED
                    : LinkOutcome.ALREADY_LINKED;
        } catch (StudentNotActiveException e) {
            return LinkOutcome.STUDENT_NOT_ACTIVE;
        } catch (GuardianNotActiveException e) {
            return LinkOutcome.GUARDIAN_NOT_ACTIVE;
        }
    }

    /** Resolves the references of the rows: the students, whether they have links, and the guardians. */
    private Facts factsOf(InstitutionId institution, List<LinkImportRules.Row> rows) {
        Map<String, StudentRef> studentRefs = new HashMap<>();
        for (List<String> chunk : chunks(LinkImportRules.studentReferences(rows))) {
            studentRefs.putAll(students.findByExternalRefs(institution, chunk));
        }
        Set<UUID> linked = new HashSet<>();
        for (List<UUID> chunk : chunks(studentRefs.values().stream().map(StudentRef::studentId).toList())) {
            linked.addAll(links.studentIdsWithCurrentLinks(institution, chunk));
        }
        Map<String, Guardian> guardianRefs = new HashMap<>();
        for (List<String> chunk : chunks(LinkImportRules.guardianReferences(rows))) {
            guardianRefs.putAll(guardians.findByExternalRefs(institution, chunk));
        }
        return new Facts(
                mapValues(studentRefs, ref -> new LinkImportRules.StudentFacts(ref.studentId(), ref.active(),
                        linked.contains(ref.studentId()))),
                mapValues(guardianRefs, guardian -> new LinkImportRules.GuardianFacts(guardian.id(),
                        guardian.active())));
    }

    private static <T, R> Map<String, R> mapValues(Map<String, T> source, Function<T, R> mapping) {
        Map<String, R> mapped = new HashMap<>();
        source.forEach((key, value) -> mapped.put(key, mapping.apply(value)));
        return mapped;
    }

    private static <T> List<List<T>> chunks(Collection<T> values) {
        List<T> all = List.copyOf(values);
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += LOOKUP_CHUNK) {
            chunks.add(all.subList(from, Math.min(from + LOOKUP_CHUNK, all.size())));
        }
        return chunks;
    }

    private static LinkImportRules.Row rowOf(LinkImportRow row) {
        return new LinkImportRules.Row(row.line(), row.studentLegacyId(), row.guardianLegacyId(),
                row.relationship(), row.financialResponsibilityPercent(), row.isPrimaryPayer(),
                row.isBillingRecipient());
    }
}
