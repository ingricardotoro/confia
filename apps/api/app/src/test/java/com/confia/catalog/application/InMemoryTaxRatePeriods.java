package com.confia.catalog.application;

import com.confia.catalog.domain.TaxRatePeriod;
import com.confia.catalog.domain.TaxRatePeriodOverlapException;
import com.confia.kernel.InstitutionId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** An in-memory port that, like the exclusion constraint of the database, refuses two overlapping periods of a rate. */
final class InMemoryTaxRatePeriods implements TaxRatePeriodRepository {

    private record Row(InstitutionId institution, TaxRatePeriod period) {
    }

    private final List<Row> rows = new ArrayList<>();

    List<TaxRatePeriod> all() {
        return rows.stream().map(Row::period).toList();
    }

    @Override
    public List<TaxRatePeriod> findByTaxRate(InstitutionId institution, UUID taxRateId) {
        return rows.stream().filter(row -> row.institution().equals(institution))
                .map(Row::period).filter(period -> period.taxRateId().equals(taxRateId))
                .sorted(Comparator.comparing(TaxRatePeriod::validFrom)).toList();
    }

    @Override
    public Optional<TaxRatePeriod> findOpen(InstitutionId institution, UUID taxRateId) {
        return findByTaxRate(institution, taxRateId).stream().filter(TaxRatePeriod::isOpen).findFirst();
    }

    @Override
    public void insert(InstitutionId institution, TaxRatePeriod period) {
        boolean overlaps = findByTaxRate(institution, period.taxRateId()).stream().anyMatch(period::overlaps);
        if (overlaps) {
            throw new TaxRatePeriodOverlapException();
        }
        rows.add(new Row(institution, period));
    }

    @Override
    public void closeValidity(InstitutionId institution, UUID periodId, LocalDate lastDay) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.institution().equals(institution) && row.period().id().equals(periodId)) {
                rows.set(i, new Row(institution, row.period().closedAt(lastDay)));
                return;
            }
        }
        throw new IllegalStateException("the open validity to close was not found");
    }
}
