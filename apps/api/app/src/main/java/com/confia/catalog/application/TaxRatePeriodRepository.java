package com.confia.catalog.application;

import com.confia.catalog.domain.TaxRatePeriod;
import com.confia.kernel.InstitutionId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the periods of a tax rate. It only inserts and reads, and closes the open validity of a period: there is
 * no operation that edits a percentage or a start, and none that deletes.
 */
public interface TaxRatePeriodRepository {

    /** Every period of the rate, oldest first. */
    List<TaxRatePeriod> findByTaxRate(InstitutionId institutionId, UUID taxRateId);

    /** The period of the rate that has no end, if any. */
    Optional<TaxRatePeriod> findOpen(InstitutionId institutionId, UUID taxRateId);

    /**
     * Inserts a period.
     *
     * @throws com.confia.catalog.domain.TaxRatePeriodOverlapException when the database refuses its validity (INV-11)
     */
    void insert(InstitutionId institutionId, TaxRatePeriod period);

    /** Closes the open validity of the period on {@code lastDay}: the only change a period ever takes. */
    void closeValidity(InstitutionId institutionId, UUID periodId, LocalDate lastDay);
}
