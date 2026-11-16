/**
 * The {@code catalog} capability: what an institution charges for. Its first cut is the payment concepts
 * (docs/cortes/catalog-payment-concepts.md): a code, a name, a category, whether the concept recurs and whether it is
 * taxable, with one use case ({@code ManagePaymentConcepts}), its jOOQ adapter and its three routes under {@code
 * /api/v1/catalog/payment-concepts}.
 *
 * <p>Out of scope for this cut: prices and their validity (price lists, cut 2), the tax rate of a concept (cut 3), the
 * accounting mapping (F3), scholarships and discounts. Nothing in this module is ever deleted: a concept is deactivated
 * with an update, and every write leaves an entry in the audit trail.
 */
package com.confia.catalog;
