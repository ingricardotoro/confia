package com.confia.catalog.application;

import java.util.List;

/** A tax rate with every period it has had, oldest first. */
public record TaxRateDetailView(TaxRateView rate, List<TaxRatePeriodView> periods) {

    public TaxRateDetailView {
        periods = List.copyOf(periods);
    }
}
