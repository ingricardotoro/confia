package com.confia.guardians.domain;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The set of links a student is to have, validated as a whole: INV-32 (docs/02-modelo-de-dominio.md) says the
 * percentages of the current links of a student add up to exactly 100. Because the first link could never add up
 * to 100 while the links are created one by one, the links of a student are replaced as a set and the set is what
 * is judged here. A set with no links is valid: a student not yet assigned.
 *
 * <p>The sum is exact decimal arithmetic ({@link BigDecimal#add}, compared with {@link BigDecimal#compareTo}):
 * 33.33 + 33.33 + 33.34 is exactly 100.00 and 99.99 or 100.01 are refused. The rules, in the order they are
 * checked: no guardian twice, at most one primary payer, at most one billing recipient, a total of 100.00.
 * Whether the guardians are active is a fact of the registry and is judged by {@link #requireActive}.
 */
public final class ResponsibilitySplit {

    public static final BigDecimal TOTAL = new BigDecimal("100.00");

    private final List<LinkDraft> links;

    private ResponsibilitySplit(List<LinkDraft> links) {
        this.links = links;
    }

    /** The set of {@code drafts}, or an {@link InvalidResponsibilitySplitException} that names the rule it breaks. */
    public static ResponsibilitySplit of(List<LinkDraft> drafts) {
        Objects.requireNonNull(drafts, "drafts");
        List<LinkDraft> links = List.copyOf(drafts);
        Set<UUID> guardians = new HashSet<>();
        int primaries = 0;
        int recipients = 0;
        BigDecimal sum = BigDecimal.ZERO.setScale(LinkDraft.SCALE);
        for (LinkDraft link : links) {
            if (!guardians.add(link.guardianId())) {
                throw new InvalidResponsibilitySplitException(InvalidResponsibilitySplitException.DUPLICATE_GUARDIAN);
            }
            primaries += link.primaryPayer() ? 1 : 0;
            recipients += link.billingRecipient() ? 1 : 0;
            sum = sum.add(link.percent());
        }
        if (primaries > 1) {
            throw new InvalidResponsibilitySplitException(InvalidResponsibilitySplitException.MULTIPLE_PRIMARY_PAYER);
        }
        if (recipients > 1) {
            throw new InvalidResponsibilitySplitException(
                    InvalidResponsibilitySplitException.MULTIPLE_BILLING_RECIPIENT);
        }
        if (!links.isEmpty() && sum.compareTo(TOTAL) != 0) {
            throw new InvalidResponsibilitySplitException(InvalidResponsibilitySplitException.SUM_NOT_100);
        }
        return new ResponsibilitySplit(links);
    }

    public List<LinkDraft> links() {
        return links;
    }

    public Set<UUID> guardianIds() {
        Set<UUID> ids = new HashSet<>();
        links.forEach(link -> ids.add(link.guardianId()));
        return ids;
    }

    /** Every guardian of the set must be among {@code activeGuardianIds}; the first that is not is refused. */
    public void requireActive(Set<UUID> activeGuardianIds) {
        for (LinkDraft link : links) {
            if (!activeGuardianIds.contains(link.guardianId())) {
                throw new GuardianNotActiveException();
            }
        }
    }

    /** The exact total of the percentages (zero for an empty set). */
    public BigDecimal total() {
        return total(links.stream().map(LinkDraft::percent).toList());
    }

    /** The exact sum of percentages, at scale 2. */
    public static BigDecimal total(List<BigDecimal> percents) {
        BigDecimal sum = BigDecimal.ZERO.setScale(LinkDraft.SCALE);
        for (BigDecimal percent : percents) {
            sum = sum.add(percent);
        }
        return sum;
    }
}
