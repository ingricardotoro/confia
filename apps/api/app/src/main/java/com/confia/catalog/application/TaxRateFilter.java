package com.confia.catalog.application;

/**
 * What a list of tax rates may be narrowed by.
 *
 * @param active only the active ({@code true}) or the inactive ({@code false}) rates; {@code null} for every one
 */
public record TaxRateFilter(Boolean active) {
}
