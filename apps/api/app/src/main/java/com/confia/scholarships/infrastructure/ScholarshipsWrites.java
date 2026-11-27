package com.confia.scholarships.infrastructure;

import com.confia.scholarships.application.RequestKeyTakenException;
import com.confia.scholarships.domain.AwardOverlapException;
import com.confia.scholarships.domain.DiscountAssignmentOverlapException;
import com.confia.scholarships.domain.DiscountBenefitLockedException;
import com.confia.scholarships.domain.DiscountScopeLockedException;
import com.confia.scholarships.domain.DiscountTermsChangedException;
import com.confia.scholarships.domain.DuplicateDiscountCodeException;
import com.confia.scholarships.domain.InvalidAssignmentStateException;
import com.confia.scholarships.domain.InvalidDiscountChangeStateException;
import com.confia.scholarships.domain.DuplicateScholarshipCodeException;
import com.confia.scholarships.domain.InactiveReferenceException;
import com.confia.scholarships.domain.InvalidAwardStateException;
import com.confia.scholarships.domain.InvalidScholarshipsValueException;
import com.confia.scholarships.domain.ScholarshipBenefitLockedException;
import com.confia.scholarships.domain.ScholarshipScopeLockedException;
import com.confia.scholarships.domain.SelfApprovalForbiddenException;
import com.confia.scholarships.domain.StaleVersionException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * What every adapter of the scholarships shares: how a write the database refused becomes a domain error or the signal to start
 * again, and how an instant crosses to a {@code timestamptz} column and back. The translation is by the {@code SQLState} and the
 * name of the constraint that the server reports, never by message text. Any other failure is rethrown untouched, so it reaches the
 * translator of the edge as an internal error and its message never reaches a client.
 */
final class ScholarshipsWrites {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String RESTRICT_VIOLATION = "23001";
    private static final String EXCLUSION_VIOLATION = "23P01";

    private ScholarshipsWrites() {
    }

    /** Runs a write and translates what the database refuses; the rest is rethrown. */
    static void writing(Runnable write) {
        try {
            write.run();
        } catch (DataAccessException e) {
            PSQLException refusal = refusal(e);
            if (refusal == null) {
                throw e;
            }
            String constraint = refusal.getServerErrorMessage().getConstraint();
            String state = refusal.getSQLState();
            if (constraint == null) {
                throw e;
            }
            switch (state) {
                case UNIQUE_VIOLATION -> unique(constraint, e);
                case EXCLUSION_VIOLATION -> exclusion(constraint, e);
                case RESTRICT_VIOLATION -> restricted(constraint, e);
                case CHECK_VIOLATION -> checked(constraint, e);
                case FOREIGN_KEY_VIOLATION -> foreignKey(constraint, e);
                default -> throw e;
            }
        }
    }

    private static void unique(String constraint, DataAccessException e) {
        switch (constraint) {
            case "scholarships_scholarship_code_uq" -> throw new DuplicateScholarshipCodeException();
            case "scholarships_award_decision_key_uq" -> throw new RequestKeyTakenException();
            case "scholarships_award_decision_kind_uq" -> throw new InvalidAwardStateException();
            case "scholarships_discount_code_uq" -> throw new DuplicateDiscountCodeException();
            case "scholarships_discount_decision_key_uq" -> throw new RequestKeyTakenException();
            case "scholarships_discount_decision_kind_uq" -> throw new InvalidAssignmentStateException();
            case "scholarships_discount_change_decision_key_uq" -> throw new RequestKeyTakenException();
            case "scholarships_discount_change_decision_version_uq" -> throw new InvalidDiscountChangeStateException();
            default -> throw e;
        }
    }

    private static void exclusion(String constraint, DataAccessException e) {
        if ("scholarships_award_overlap_excl".equals(constraint)) {
            throw new AwardOverlapException();
        }
        if ("scholarships_discount_assignment_overlap_excl".equals(constraint)) {
            throw new DiscountAssignmentOverlapException();
        }
        throw e;
    }

    private static void restricted(String constraint, DataAccessException e) {
        if ("scholarships_scholarship_benefit_locked_chk".equals(constraint)) {
            throw new ScholarshipBenefitLockedException();
        }
        if ("scholarships_scholarship_scope_locked_chk".equals(constraint)) {
            throw new ScholarshipScopeLockedException();
        }
        if ("scholarships_discount_benefit_locked_chk".equals(constraint)) {
            throw new DiscountBenefitLockedException();
        }
        if ("scholarships_discount_scope_locked_chk".equals(constraint)) {
            throw new DiscountScopeLockedException();
        }
        throw e;
    }

    private static void checked(String constraint, DataAccessException e) {
        switch (constraint) {
            case "scholarships_award_in_year_chk" -> throw new InvalidScholarshipsValueException("validFrom",
                    "outside-academic-year");
            case "scholarships_award_scholarship_active_chk" -> throw new InactiveReferenceException();
            case "scholarships_award_decision_self_chk" -> throw new SelfApprovalForbiddenException();
            case "scholarships_award_decision_state_chk" -> throw new InvalidAwardStateException();
            case "scholarships_discount_assignment_active_chk" -> throw new InactiveReferenceException();
            case "scholarships_discount_assignment_kind_chk" -> throw new InvalidScholarshipsValueException("discountId",
                    "not-agreement");
            case "scholarships_discount_assignment_in_validity_chk" -> throw new InvalidScholarshipsValueException("validFrom",
                    "outside-discount-validity");
            case "scholarships_discount_validity_assignments_chk" -> throw new InvalidScholarshipsValueException("validTo",
                    "assignments-outside-validity");
            case "scholarships_discount_decision_self_chk" -> throw new SelfApprovalForbiddenException();
            case "scholarships_discount_decision_state_chk" -> throw new InvalidAssignmentStateException();
            case "scholarships_discount_change_decision_self_chk" -> throw new SelfApprovalForbiddenException();
            case "scholarships_discount_change_decision_state_chk" -> throw new InvalidDiscountChangeStateException();
            case "scholarships_discount_change_decision_version_chk" -> throw new StaleVersionException();
            case "scholarships_discount_assignment_terms_chk" -> throw new DiscountTermsChangedException();
            default -> throw e;
        }
    }

    private static void foreignKey(String constraint, DataAccessException e) {
        if ("scholarships_scholarship_concept_concept_fk".equals(constraint)
                || "scholarships_discount_concept_concept_fk".equals(constraint)) {
            throw new InvalidScholarshipsValueException("conceptIds", "not-found");
        }
        throw e;
    }

    private static PSQLException refusal(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres && postgres.getServerErrorMessage() != null) {
                return postgres;
            }
        }
        return null;
    }

    static OffsetDateTime toDatabase(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant fromDatabase(OffsetDateTime value) {
        return value.toInstant();
    }
}
