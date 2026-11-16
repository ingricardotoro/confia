package com.confia.catalog.application;

import java.util.List;

/** A price list with every validity of every price it holds. */
public record PriceListDetailView(PriceListView list, List<PriceListItemView> items) {

    public PriceListDetailView {
        items = List.copyOf(items);
    }
}
