package com.confia.charges.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The map of posting accounts of an institution (docs/cortes/charges-accrual.md): the receivable account, the tax payable
 * account and the revenue account of each category of concept, with a default revenue account for the categories that have
 * none of their own. It is DATA loaded per institution; nothing of the chart of accounts is code. A map without the
 * receivable, the tax payable or a revenue for a category is incomplete, and an incomplete map makes the run refuse.
 */
public final class PostingAccountMap {

    /** One row of the map: its purpose, the category (revenue only, {@code null} is the default) and the account. */
    public record Entry(PostingPurpose purpose, String conceptCategory, UUID ledgerAccountId) {

        public Entry {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ledgerAccountId, "ledgerAccountId");
            if (conceptCategory != null && purpose != PostingPurpose.REVENUE) {
                throw new InvalidChargesValueException();
            }
        }
    }

    private final UUID receivable;
    private final UUID taxPayable;
    private final UUID defaultRevenue;
    private final Map<String, UUID> revenueByCategory;

    public PostingAccountMap(List<Entry> entries) {
        UUID receivableAccount = null;
        UUID taxAccount = null;
        UUID revenueDefault = null;
        Map<String, UUID> byCategory = new HashMap<>();
        for (Entry entry : Objects.requireNonNull(entries, "entries")) {
            switch (entry.purpose()) {
                case RECEIVABLE -> receivableAccount = entry.ledgerAccountId();
                case TAX_PAYABLE -> taxAccount = entry.ledgerAccountId();
                case REVENUE -> {
                    if (entry.conceptCategory() == null) {
                        revenueDefault = entry.ledgerAccountId();
                    } else {
                        byCategory.put(entry.conceptCategory(), entry.ledgerAccountId());
                    }
                }
            }
        }
        this.receivable = receivableAccount;
        this.taxPayable = taxAccount;
        this.defaultRevenue = revenueDefault;
        this.revenueByCategory = Map.copyOf(byCategory);
    }

    public Optional<UUID> receivable() {
        return Optional.ofNullable(receivable);
    }

    public Optional<UUID> taxPayable() {
        return Optional.ofNullable(taxPayable);
    }

    /** The revenue account of the category, or the default one when the category has none of its own. */
    public Optional<UUID> revenueFor(String category) {
        return Optional.ofNullable(revenueByCategory.getOrDefault(category, defaultRevenue));
    }

    /** Whether the map can post a charge of every category of the given concepts, and of their tax when any is taxed. */
    public boolean completeFor(java.util.Collection<String> categories, boolean taxed) {
        return receivable != null && (!taxed || taxPayable != null) && categories.stream().allMatch(c -> revenueFor(c).isPresent());
    }
}
