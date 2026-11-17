package com.confia.charges.application;

import java.util.List;

/** A charge plan with its items. */
public record ChargePlanDetailView(ChargePlanView plan, List<ChargePlanItemView> items) {

    public ChargePlanDetailView {
        items = List.copyOf(items);
    }
}
