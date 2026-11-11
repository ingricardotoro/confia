package com.confia.students.application;

import java.util.List;

/**
 * One page of a list: the rows asked for, how many rows match in all, and the page and size that were
 * served.
 */
public record Page<T>(List<T> items, long total, int page, int size) {

    public Page {
        items = List.copyOf(items);
    }

    /** The same page with each row converted. */
    public <R> Page<R> map(java.util.function.Function<T, R> conversion) {
        return new Page<>(items.stream().map(conversion).toList(), total, page, size);
    }
}
