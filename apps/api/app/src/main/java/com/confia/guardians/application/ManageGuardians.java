package com.confia.guardians.application;

import com.confia.guardians.domain.Guardian;
import com.confia.guardians.domain.GuardianCode;
import com.confia.guardians.domain.GuardianLink;
import com.confia.guardians.domain.GuardianStatus;
import com.confia.guardians.domain.InvalidGuardianException;
import com.confia.guardians.domain.NationalId;
import com.confia.guardians.domain.ProtectedValueChange;
import com.confia.guardians.domain.ResourceNotFoundException;
import com.confia.guardians.domain.Rtn;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Registers, edits, reads and lists the guardians of the institution of the actor (docs/cortes/guardians-registry.md).
 * Nothing is deleted: a guardian is made inactive with {@link #update}. The code ({@code P-000001}, from the counter
 * of the institution, taken under the lock the repository holds until the transaction ends) never changes and the
 * national identity document is unique in the institution, which the database enforces through its blind index.
 *
 * <p>The document and the RTN are protected (encrypted, the document also indexed) before they are stored and never
 * come back out: views and audit entries carry only whether there is one. On an edit an omitted one is kept, a blank
 * one removes it and any other replaces it. A guardian can be made inactive while it still has current links: the
 * links stay (a decision of the staff) and the detail shows them so the screen can warn.
 */
public final class ManageGuardians {

    /** The fields a list may be sorted by: {@code code} is the code of the guardian. */
    public static final Set<String> SORT_FIELDS = Set.of("lastName", "code", "status");

    static final int MIN_SEARCH_LENGTH = 2;

    private final TransactionRunner transactionRunner;
    private final GuardianRepository guardians;
    private final GuardianLinkRepository links;
    private final StudentDirectory students;
    private final GuardianSecretsProtector protector;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageGuardians(TransactionRunner transactionRunner, GuardianRepository guardians,
            GuardianLinkRepository links, StudentDirectory students, GuardianSecretsProtector protector,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.links = Objects.requireNonNull(links, "links");
        this.students = Objects.requireNonNull(students, "students");
        this.protector = Objects.requireNonNull(protector, "protector");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public GuardianView create(AuthenticatedActor actor, String requestId, String firstName, String lastName,
            String nationalId, String rtn, String email, String phone, String address, String preferredChannel) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = GuardiansAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        NationalId document = nationalId == null || nationalId.isBlank() ? null : NationalId.of(nationalId);
        Rtn taxNumber = rtn == null || rtn.isBlank() ? null : Rtn.of(rtn);
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            String code = generatedCode(institution);
            UUID id = UUID.randomUUID();
            Guardian guardian = Guardian.create(id, code, firstName, lastName, document, taxNumber, email, phone,
                    address, preferredChannel, now());
            ProtectedNationalId protectedDocument =
                    document == null ? null : protector.protect(institution, id, document);
            String protectedRtn = taxNumber == null ? null : protector.protect(institution, id, taxNumber);
            guardians.insert(institution, guardian, protectedDocument, protectedRtn);
            auditLogWriter.append(GuardiansAudit.created(actor, auditRequestId, guardian));
            return GuardianView.of(guardian, List.of());
        });
    }

    public GuardianView update(AuthenticatedActor actor, String requestId, UUID id, String firstName,
            String lastName, String nationalId, String rtn, String email, String phone, String address,
            String preferredChannel, String status) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = GuardiansAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        ProtectedValueChange<NationalId> documentChange = ProtectedValueChange.ofNationalId(nationalId);
        ProtectedValueChange<Rtn> rtnChange = ProtectedValueChange.ofRtn(rtn);
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Guardian before = guardians.lockById(institution, id).orElseThrow(ResourceNotFoundException::new);
            Guardian after = before.edit(firstName, lastName, documentChange, rtnChange, email, phone, address,
                    preferredChannel, status, now());
            guardians.update(institution, after, documentStorage(institution, id, documentChange),
                    rtnStorage(institution, id, rtnChange));
            auditLogWriter.append(GuardiansAudit.updated(actor, auditRequestId, before, after, documentChange,
                    rtnChange));
            return GuardianView.of(after, linkedStudents(institution, id));
        });
    }

    public GuardianView get(AuthenticatedActor actor, String requestId, UUID id) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = GuardiansAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            Guardian guardian = guardians.findById(institution, id).orElseThrow(ResourceNotFoundException::new);
            return GuardianView.of(guardian, linkedStudents(institution, id));
        });
    }

    /**
     * One page of the guardians. {@code search} is stripped: blank means no search, one character is refused, two
     * or more look in the first name, the last name and the code.
     */
    public Page<GuardianView> list(AuthenticatedActor actor, String requestId, int page, int size, String sort,
            String search, String status) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = GuardiansAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        PageRequest request = PageRequest.of(page, size, sort, SORT_FIELDS, "lastName", true);
        GuardianFilter filter = new GuardianFilter(searchText(search),
                status == null ? null : GuardianStatus.parse(status));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> guardians.list(institution, request, filter).map(guardian -> GuardianView.of(guardian, null)));
    }

    /** The students the guardian is linked to now, with the link; the student of a link always exists. */
    private List<LinkedStudentView> linkedStudents(InstitutionId institution, UUID guardianId) {
        List<GuardianLink> current = links.currentOfGuardian(institution, guardianId);
        Map<UUID, StudentRef> refs = students.findAll(institution,
                current.stream().map(GuardianLink::studentId).distinct().toList());
        return current.stream().map(link -> {
            StudentRef student = refs.get(link.studentId());
            return new LinkedStudentView(link.id(), link.studentId(), student.studentCode(), student.firstName(),
                    student.lastName(), link.relationship().name(), link.percent(), link.primaryPayer(),
                    link.billingRecipient(), link.validFrom());
        }).toList();
    }

    /** The instant of the write, to the microsecond the column keeps, so an answer equals what a read returns. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private String generatedCode(InstitutionId institution) {
        String code;
        do {
            code = GuardianCode.generated(guardians.nextCodeSequence(institution));
        } while (guardians.codeExists(institution, code));
        return code;
    }

    private Storage<ProtectedNationalId> documentStorage(InstitutionId institution, UUID guardianId,
            ProtectedValueChange<NationalId> change) {
        return switch (change) {
            case ProtectedValueChange.Keep<NationalId> keep -> new Storage.Keep<>();
            case ProtectedValueChange.Remove<NationalId> remove -> new Storage.Clear<>();
            case ProtectedValueChange.Replace<NationalId> replace ->
                    new Storage.Store<>(protector.protect(institution, guardianId, replace.value()));
        };
    }

    private Storage<String> rtnStorage(InstitutionId institution, UUID guardianId, ProtectedValueChange<Rtn> change) {
        return switch (change) {
            case ProtectedValueChange.Keep<Rtn> keep -> new Storage.Keep<>();
            case ProtectedValueChange.Remove<Rtn> remove -> new Storage.Clear<>();
            case ProtectedValueChange.Replace<Rtn> replace ->
                    new Storage.Store<>(protector.protect(institution, guardianId, replace.value()));
        };
    }

    private static String searchText(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String stripped = search.strip();
        if (stripped.length() < MIN_SEARCH_LENGTH) {
            throw new InvalidGuardianException();
        }
        return stripped;
    }
}
