package com.confia.ledger.application;

import java.util.List;

/** A page of a list; {@code total} counts every row, not only the page. */
public record Page<T>(List<T> items, long total, int page, int size) {

    public Page {
        items = List.copyOf(items);
    }
}
