package com.confia.guardians.application;

import com.confia.guardians.domain.Guardian;
import com.confia.guardians.domain.GuardianLink;
import com.confia.guardians.domain.InvalidResponsibilitySplitException;
import com.confia.guardians.domain.LinkDraft;
import com.confia.guardians.domain.LinkReplacement;
import com.confia.guardians.domain.ResourceNotFoundException;
import com.confia.guardians.domain.ResponsibilitySplit;
import com.confia.guardians.domain.StudentNotActiveException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and replaces the guardians of a student (docs/cortes/guardians-registry.md). The links of a student are
 * replaced as an atomic set, because INV-32 (the current percentages add up to exactly 100) could never hold for
 * the first link if the links were created one by one: the set is validated in exact decimal arithmetic, with one
 * primary payer at most, one billing recipient at most, no guardian twice and every guardian active. A student with
 * no links is valid (not yet assigned).
 *
 * <p>The replacement keeps the history: a link that is absent from the set or whose percentage, relationship or
 * flags differ is closed (its end is today in Honduras) and a new one opens; an identical link is left alone and a
 * replacement that changes nothing writes nothing and leaves no audit entry. A closed link is never reopened nor
 * edited.
 *
 * <p>Race safety. The replacement locks the row of the student ({@code FOR UPDATE}, through the directory that
 * {@code students} publishes) before it reads the current links, so two replacements of the same student take
 * turns and the second sees, and replaces, what the first committed; then it locks the guardians involved in share
 * mode in identifier order, so a concurrent deactivation waits. The lock order is always student, then guardians.
 * The unique indexes of the database (one current link per guardian and student, one primary payer, one billing
 * recipient) are the last word.
 */
public final class ManageGuardianLinks {

    private final TransactionRunner transactionRunner;
    private final GuardianRepository guardians;
    private final GuardianLinkRepository links;
    private final StudentDirectory students;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageGuardianLinks(TransactionRunner transactionRunner, GuardianRepository guardians,
            GuardianLinkRepository links, StudentDirectory students, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.links = Objects.requireNonNull(links, "links");
        this.students = Objects.requireNonNull(students, "students");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The current links of the student with their exact total. */
    public StudentGuardiansView of(AuthenticatedActor actor, String requestId, UUID studentId) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = GuardiansAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            StudentRef student = students.find(institution, studentId).orElseThrow(ResourceNotFoundException::new);
            List<GuardianLink> current = links.currentOfStudent(institution, studentId);
            return view(student, current, byId(guardians.findAll(institution, guardianIdsOf(current))));
        });
    }

    /**
     * Replaces the current links of the student with {@code requested}. An empty list removes every guardian.
     *
     * @throws RejectedSplitException the set breaks INV-32, one primary payer, one billing recipient or no
     *     guardian twice
     */
    public StudentGuardiansView replace(AuthenticatedActor actor, String requestId, UUID studentId,
            List<LinkRequest> requested) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(requested, "requested");
        UUID auditRequestId = GuardiansAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        ResponsibilitySplit split = split(requested);
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            StudentRef student = students.lock(institution, studentId).orElseThrow(ResourceNotFoundException::new);
            if (!student.active()) {
                throw new StudentNotActiveException();
            }
            List<GuardianLink> current = links.currentOfStudent(institution, studentId);
            Set<UUID> involved = new LinkedHashSet<>(split.guardianIds());
            involved.addAll(guardianIdsOf(current));
            Map<UUID, Guardian> found = byId(guardians.lockAllShared(institution, involved));
            if (!found.keySet().containsAll(split.guardianIds())) {
                throw new ResourceNotFoundException();
            }
            Set<UUID> active = new HashSet<>();
            found.values().forEach(guardian -> {
                if (guardian.active()) {
                    active.add(guardian.id());
                }
            });
            split.requireActive(active);

            Instant now = now();
            LinkReplacement replacement = LinkReplacement.of(current, split, studentId, LinkReplacement.dayOf(now),
                    actor.accountId(), now, UUID::randomUUID);
            List<GuardianLink> after = new ArrayList<>(replacement.kept());
            after.addAll(replacement.opened());
            if (replacement.changed()) {
                LocalDate today = LinkReplacement.dayOf(now);
                links.close(institution, replacement.closed().stream().map(GuardianLink::id).toList(), today);
                links.insertAll(institution, replacement.opened());
                Map<UUID, String> codes = new HashMap<>();
                found.values().forEach(guardian -> codes.put(guardian.id(), guardian.guardianCode()));
                auditLogWriter.append(GuardiansAudit.linksReplaced(actor, auditRequestId, studentId,
                        student.studentCode(), current, after, codes));
            }
            return view(student, after, found);
        });
    }

    private static ResponsibilitySplit split(List<LinkRequest> requested) {
        List<LinkDraft> drafts = requested.stream().map(link -> LinkDraft.of(link.guardianId(), link.relationship(),
                link.financialResponsibilityPercent(), link.primaryPayer(), link.billingRecipient())).toList();
        try {
            return ResponsibilitySplit.of(drafts);
        } catch (InvalidResponsibilitySplitException e) {
            throw new RejectedSplitException(e.reason());
        }
    }

    private static StudentGuardiansView view(StudentRef student, List<GuardianLink> current,
            Map<UUID, Guardian> guardianById) {
        List<StudentGuardianView> views = current.stream().map(link -> {
            Guardian guardian = guardianById.get(link.guardianId());
            return new StudentGuardianView(link.id(), guardian.id(), guardian.guardianCode(), guardian.firstName(),
                    guardian.lastName(), guardian.status().name(), link.relationship().name(), link.percent(),
                    link.primaryPayer(), link.billingRecipient(), link.validFrom());
        }).toList();
        return new StudentGuardiansView(student.studentId(), student.studentCode(),
                ResponsibilitySplit.total(current.stream().map(GuardianLink::percent).toList()), views);
    }

    private static List<UUID> guardianIdsOf(List<GuardianLink> current) {
        return current.stream().map(GuardianLink::guardianId).distinct().toList();
    }

    private static Map<UUID, Guardian> byId(List<Guardian> found) {
        Map<UUID, Guardian> byId = new HashMap<>();
        found.forEach(guardian -> byId.put(guardian.id(), guardian));
        return byId;
    }

    /** The instant of the write, to the microsecond the column keeps, so an answer equals what a read returns. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
