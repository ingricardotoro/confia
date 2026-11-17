/**
 * The {@code catalog} capability: what an institution charges for and at what price. Its first cut is the payment
 * concepts (docs/cortes/catalog-payment-concepts.md): a code, a name, a category, whether the concept recurs and whether
 * it is taxable, with one use case ({@code ManagePaymentConcepts}), its jOOQ adapter and its three routes under {@code
 * /api/v1/catalog/payment-concepts}. Its second cut is the price lists (docs/cortes/catalog-price-lists.md): a list per
 * academic year, optionally per modality or grade, with one price per concept and validity, with the use case {@code
 * ManagePriceLists} and its five routes under {@code /api/v1/catalog/price-lists}. A change of price is a new validity;
 * the previous one is closed, never edited (INV-11, INV-12), and an edit of a list or of a concept carries the version
 * the editor read. Its third cut is the tax rates (docs/cortes/catalog-tax-rates.md): a rate with a code and a name and a
 * history of periods (a percentage with a validity, closed by the next one, never edited), with the use case {@code
 * ManageTaxRates} and its five routes under {@code /api/v1/catalog/tax-rates}; a taxable payment concept points at its
 * rate. No percentage is written in the code: the administrator loads them.
 *
 * <p>Out of scope: the calculation of taxes and the treatment of exempt operations (F3, F4), the accounting mapping (F3), scholarships and discounts, and the
 * choice of the price that applies to a student (the accrual engine of F3). Nothing in this module is ever deleted: a
 * concept is deactivated, a list is archived and a price is closed, and every write leaves an entry in the audit trail.
 * The academic year, modality and grade of a list are proven through the directory that {@code organization} publishes,
 * never by importing its domain nor reading its tables.
 */
package com.confia.catalog;
