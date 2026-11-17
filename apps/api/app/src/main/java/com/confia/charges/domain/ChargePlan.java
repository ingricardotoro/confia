package com.confia.charges.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What an institution accrues, how often and to whom (docs/cortes/charges-plans.md, docs/02-modelo-de-dominio.md section
 * 3.4). A plan belongs to an academic year and targets one modality, one grade or one section; none of the three changes
 * once the plan exists. Its items are {@link ChargePlanItem}s, one per payment concept.
 *
 * <p>A plan is born as a {@link PlanStatus#DRAFT}: while it is one its items are added and removed and its name,
 * frequency and due day are edited. {@link PlanStatus#ACTIVE} freezes it: only the name changes and the plan may be
 * archived. {@link PlanStatus#ARCHIVED} is final and changes nothing. To change an active plan another is created, so a
 * plan that already served to accrue never changes under its charges. Activation needs at least one item. There is no
 * reactivation and no deletion.
 *
 * <p>An edit asks for the version the editor saw ({@link StaleVersionException} when it is not the current one) and
 * advances it. The due day is from 1 to 28, so it exists in every month.
 *
 * @param targetRefId the modality, the grade or the section the plan applies to
 * @param dueDayOfMonth the day of the month charges fall due, from 1 to 28
 */
public record ChargePlan(UUID id, UUID academicYearId, String name, PlanTargetScope targetScope, UUID targetRefId,
        PlanFrequency frequency, int dueDayOfMonth, PlanStatus status, long version, Instant createdAt,
        Instant updatedAt) {

    public static final int MIN_DUE_DAY = 1;
    public static final int MAX_DUE_DAY = 28;

    public ChargePlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (academicYearId == null || targetScope == null || targetRefId == null || frequency == null
                || status == null || version < 0 || dueDayOfMonth < MIN_DUE_DAY || dueDayOfMonth > MAX_DUE_DAY) {
            throw new InvalidChargesValueException();
        }
        name = Names.name(name);
    }

    /** A new plan, as a draft at version zero. */
    public static ChargePlan create(UUID id, UUID academicYearId, String name, PlanTargetScope targetScope,
            UUID targetRefId, PlanFrequency frequency, int dueDayOfMonth, Instant now) {
        return new ChargePlan(id, academicYearId, name, targetScope, targetRefId, frequency, dueDayOfMonth,
                PlanStatus.DRAFT, 0, now, now);
    }

    /**
     * The same plan with a new name, frequency, due day and status, and the version advanced.
     *
     * @param expectedVersion the version the editor saw
     * @param itemCount how many items the plan holds now, which an activation needs to be at least one
     * @throws StaleVersionException when the version is not the current one
     * @throws PlanNotEditableException when the plan is archived, when the status is not the same one or the next one
     *     (draft to active, active to archived), or when the frequency or the due day of an active plan change
     * @throws PlanWithoutItemsException when a draft with no item is activated
     */
    public ChargePlan edit(long expectedVersion, String newName, PlanFrequency newFrequency, int newDueDayOfMonth,
            PlanStatus newStatus, int itemCount, Instant now) {
        if (expectedVersion != version) {
            throw new StaleVersionException();
        }
        if (newName == null || newFrequency == null || newStatus == null) {
            throw new InvalidChargesValueException();
        }
        if (status == PlanStatus.ARCHIVED) {
            throw new PlanNotEditableException();
        }
        boolean next = (status == PlanStatus.DRAFT && newStatus == PlanStatus.ACTIVE)
                || (status == PlanStatus.ACTIVE && newStatus == PlanStatus.ARCHIVED);
        if (newStatus != status && !next) {
            throw new PlanNotEditableException();
        }
        if (status == PlanStatus.ACTIVE && (newFrequency != frequency || newDueDayOfMonth != dueDayOfMonth)) {
            throw new PlanNotEditableException();
        }
        if (status == PlanStatus.DRAFT && newStatus == PlanStatus.ACTIVE && itemCount < 1) {
            throw new PlanWithoutItemsException();
        }
        return new ChargePlan(id, academicYearId, newName, targetScope, targetRefId, newFrequency,
                newDueDayOfMonth, newStatus, version + 1, createdAt, now);
    }

    /** @throws PlanNotEditableException when the plan is not a draft: only a draft takes or loses items */
    public void requireDraft() {
        if (status != PlanStatus.DRAFT) {
            throw new PlanNotEditableException();
        }
    }
}
