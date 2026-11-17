package com.confia.catalog.application.directory;

import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.kernel.Percentage;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port, read-only, through which {@code charges} asks {@code catalog} for what the accrual engine prices a charge
 * with: the terms of a payment concept, its prices in the active price lists of an academic year and a tax rate with its
 * validities (docs/cortes/charges-accrual.md). Scoped to the institution given. It never writes.
 *
 * <p>Every method takes {@code lockShare}: with it the rows read are locked in share mode until the end of the transaction
 * ({@code SELECT ... FOR SHARE}), so an edit or the closing of a validity waits for the charge that is being written and
 * then fails on the database rule that protects it (INV-46). Without it nothing is locked, which is what a preview, which
 * runs in a read-only transaction, needs.
 */
public interface PricingDirectory {

    Optional<ConceptTerms> concept(InstitutionId institutionId, UUID paymentConceptId, boolean lockShare);

    /** The prices of the concept in the {@code ACTIVE} price lists of the academic year, whatever their validity. */
    List<PriceEntry> prices(InstitutionId institutionId, UUID academicYearId, UUID paymentConceptId, boolean lockShare);

    Optional<TaxRateTerms> taxRate(InstitutionId institutionId, UUID taxRateId, boolean lockShare);

    /** The terms of a payment concept: its state, category, whether it is taxable and its tax rate, if any. */
    record ConceptTerms(UUID conceptId, boolean active, String category, boolean taxable, UUID taxRateId) {

        public ConceptTerms {
            Objects.requireNonNull(conceptId, "conceptId");
            Objects.requireNonNull(category, "category");
        }
    }

    /**
     * A price of a concept: the item, the level of its list ({@code INSTITUTION}, {@code MODALITY} or {@code GRADE}) and the
     * modality or grade it belongs to, the amount and the validity (the last day is {@code null} while it is open).
     */
    record PriceEntry(UUID priceListItemId, String scope, UUID scopeRefId, Money amount, LocalDate validFrom,
            LocalDate validTo) {
    }

    /** A tax rate: whether it is active and its validities. */
    record TaxRateTerms(UUID taxRateId, boolean active, List<TaxValidity> validities) {

        public TaxRateTerms {
            validities = List.copyOf(validities);
        }
    }

    /** A validity of a tax rate: the percentage in force from the first to the last day (open while it is {@code null}). */
    record TaxValidity(UUID periodId, Percentage percent, LocalDate validFrom, LocalDate validTo) {
    }
}
