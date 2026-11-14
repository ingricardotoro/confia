package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.Modality;
import com.confia.organization.domain.ResourceNotFoundException;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates, edits and lists the modalities of the institution of the actor. Nothing is deleted: a
 * modality is deactivated with {@link #update}. The code is unique in the institution, which the
 * database enforces. Deactivating a modality leaves its grades and their sections as they are.
 */
public final class ManageModalities {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("code", "name");

    private final TransactionRunner transactionRunner;
    private final ModalityRepository modalities;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageModalities(TransactionRunner transactionRunner, ModalityRepository modalities,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.modalities = Objects.requireNonNull(modalities, "modalities");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ModalityView create(AuthenticatedActor actor, String requestId, String code, String name) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = OrganizationAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Modality modality = Modality.create(UUID.randomUUID(), code, name, clock.instant());
            modalities.insert(institution, modality);
            auditLogWriter.append(OrganizationAudit.created(actor, auditRequestId,
                    OrganizationAudit.MODALITY, modality.id(), OrganizationAudit.json(modality)));
            return ModalityView.of(modality);
        });
    }

    public ModalityView update(AuthenticatedActor actor, String requestId, UUID id, String code,
            String name, boolean active) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = OrganizationAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Modality before = modalities.findById(institution, id)
                    .orElseThrow(ResourceNotFoundException::new);
            Modality after = before.edit(code, name, active, clock.instant());
            modalities.update(institution, after);
            auditLogWriter.append(OrganizationAudit.updated(actor, auditRequestId,
                    OrganizationAudit.MODALITY, id, OrganizationAudit.json(before),
                    OrganizationAudit.json(after)));
            return ModalityView.of(after);
        });
    }

    public Page<ModalityView> list(AuthenticatedActor actor, String requestId, int page, int size,
            String sort) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = OrganizationAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        PageRequest request = PageRequest.of(page, size, sort, SORT_FIELDS, "code", true);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> modalities.list(institution, request).map(ModalityView::of));
    }
}
