package com.confia.catalog.application;

import com.confia.catalog.domain.PaymentCategory;

/**
 * The optional filters of the list of payment concepts; a {@code null} member does not filter.
 *
 * @param category only the concepts of this category
 * @param active only the active ({@code true}) or the inactive ({@code false}) ones
 */
public record PaymentConceptFilter(PaymentCategory category, Boolean active) {
}
