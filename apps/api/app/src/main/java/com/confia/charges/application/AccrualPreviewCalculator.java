package com.confia.charges.application;

import com.confia.catalog.application.directory.PricingDirectory;
import com.confia.charges.domain.AccrualErrorCode;
import com.confia.charges.domain.AccrualPeriods;
import com.confia.charges.domain.AccrualRejection;
import com.confia.charges.domain.AppliedBenefit;
import com.confia.charges.domain.ChargePlan;
import com.confia.charges.domain.ChargePlanItem;
import com.confia.charges.domain.PlanStatus;
import com.confia.charges.domain.PostingAccountMap;
import com.confia.charges.domain.PostingPurpose;
import com.confia.charges.domain.StudentPlacement;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.ledger.application.api.LedgerAccountDirectory;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.scholarships.application.api.DiscountBenefits;
import com.confia.shared.security.Digests;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The calculation of a whole period of a plan with no write: the population, what each student and concept would produce, the
 * preconditions of a run and the digest of all of it. The preview shows its result and the run recomputes it at the start to
 * compare digests. It runs inside the transaction of its caller.
 */
final class AccrualPreviewCalculator {

    private final AccrualEngine engine;
    private final PricingDirectory pricing;
    private final PostingAccountRepository postingAccounts;
    private final LedgerAccountDirectory ledgerAccounts;
    private final AwardBenefits awards;
    private final DiscountBenefits discounts;

    AccrualPreviewCalculator(AccrualEngine engine, PricingDirectory pricing, PostingAccountRepository postingAccounts,
            LedgerAccountDirectory ledgerAccounts, AwardBenefits awards, DiscountBenefits discounts) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.postingAccounts = Objects.requireNonNull(postingAccounts, "postingAccounts");
        this.ledgerAccounts = Objects.requireNonNull(ledgerAccounts, "ledgerAccounts");
        this.awards = Objects.requireNonNull(awards, "awards");
        this.discounts = Objects.requireNonNull(discounts, "discounts");
    }

    /** The whole calculation: every line, the population and the preconditions that do not depend on the clock. */
    record Calculation(List<AccrualEngine.Line> lines, List<StudentPlacement> population, List<AccrualRejection> blockers,
            String digest, boolean targetOutsideYear) {
    }

    Calculation calculate(InstitutionId institution, ChargePlan plan, List<ChargePlanItem> items, LocalDate periodStart,
            boolean lockShare) {
        List<AccrualPeriods.PlanPeriod> periods = AccrualPeriods.startingAt(AccrualPeriods.of(plan, items), periodStart);
        List<AccrualRejection> blockers = new ArrayList<>(preconditions(institution, plan, periods));
        Optional<List<StudentPlacement>> found = periods.isEmpty() ? Optional.of(List.<StudentPlacement>of())
                : engine.population(institution, plan, periods.get(0).period());
        if (found.isEmpty()) {
            blockers.add(AccrualRejection.PLAN_TARGET_OUTSIDE_YEAR);
            return new Calculation(List.of(), List.of(), List.copyOf(blockers), digestOf(List.of()), true);
        }
        if (!blockers.contains(AccrualRejection.POSTING_ACCOUNTS_INCOMPLETE)
                && !benefitsAccountReady(institution, periods, found.get())) {
            blockers.add(AccrualRejection.POSTING_ACCOUNTS_INCOMPLETE);
        }
        List<AccrualEngine.Line> lines = new ArrayList<>();
        for (StudentPlacement student : found.get()) {
            lines.addAll(engine.evaluateStudent(institution, plan, student, periods, lockShare));
        }
        lines.sort(Comparator.comparing(AccrualEngine.Line::studentId)
                .thenComparing(AccrualEngine.Line::paymentConceptId));
        return new Calculation(List.copyOf(lines), found.get(), List.copyOf(blockers), digestOf(lines), false);
    }

    /** The preconditions of a run that depend only on the plan and the map of accounts; the clock is the caller's. */
    List<AccrualRejection> preconditions(InstitutionId institution, ChargePlan plan,
            List<AccrualPeriods.PlanPeriod> periods) {
        List<AccrualRejection> blockers = new ArrayList<>();
        if (plan.status() != PlanStatus.ACTIVE) {
            blockers.add(AccrualRejection.PLAN_NOT_ACTIVE);
        }
        if (!postingAccountsReady(institution, periods)) {
            blockers.add(AccrualRejection.POSTING_ACCOUNTS_INCOMPLETE);
        }
        return blockers;
    }

    /**
     * Whether the map of accounts can post every charge of the period: a receivable that holds student balances, a revenue
     * account for each category of the concepts, and a tax payable account when any of them is taxable; all of them exist,
     * are active and are of the right type.
     */
    boolean postingAccountsReady(InstitutionId institution, List<AccrualPeriods.PlanPeriod> periods) {
        PostingAccountMap map = new PostingAccountMap(postingAccounts.list(institution));
        Set<String> categories = new HashSet<>();
        boolean taxed = false;
        for (AccrualPeriods.PlanPeriod period : periods) {
            Optional<PricingDirectory.ConceptTerms> concept = pricing.concept(institution, period.paymentConceptId(),
                    false);
            if (concept.isEmpty()) {
                return false;
            }
            categories.add(concept.get().category());
            taxed |= concept.get().taxable();
        }
        if (!map.completeFor(categories, taxed)) {
            return false;
        }
        Set<UUID> ids = new HashSet<>();
        map.receivable().ifPresent(ids::add);
        if (taxed) {
            map.taxPayable().ifPresent(ids::add);
        }
        categories.forEach(category -> map.revenueFor(category).ifPresent(ids::add));
        Map<UUID, LedgerAccountDirectory.LedgerAccountRef> accounts = ledgerAccounts.find(institution, ids);
        if (!isUsable(accounts.get(map.receivable().orElseThrow()), "ASSET", true)) {
            return false;
        }
        if (taxed && !isUsable(accounts.get(map.taxPayable().orElseThrow()), "LIABILITY", false)) {
            return false;
        }
        for (String category : categories) {
            if (!isUsable(accounts.get(map.revenueFor(category).orElseThrow()), "INCOME", false)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the period can post its benefits: nothing to check when no approved award or discount in force at the first day of the period
     * covers a concept of the plan for a student of the population; otherwise the map must name an active account for the
     * purpose {@code BENEFITS}, of a type that purpose accepts and that holds no student balances (assumption S4 of
     * docs/cortes/scholarships-awards.md, pending the accountant).
     */
    boolean benefitsAccountReady(InstitutionId institution, List<AccrualPeriods.PlanPeriod> periods,
            List<StudentPlacement> population) {
        if (periods.isEmpty() || population.isEmpty()) {
            return true;
        }
        LocalDate on = periods.get(0).period().start();
        Set<UUID> concepts = new HashSet<>();
        periods.forEach(period -> concepts.add(period.paymentConceptId()));
        List<UUID> students = population.stream().map(StudentPlacement::studentId).toList();
        if (!awards.anyApplicable(institution, students, concepts, on) && !discounts.anyApplicable(institution, students, concepts, on)) {
            return true;
        }
        Optional<UUID> account = new PostingAccountMap(postingAccounts.list(institution)).benefits();
        if (account.isEmpty()) {
            return false;
        }
        LedgerAccountDirectory.LedgerAccountRef ref = ledgerAccounts.find(institution, Set.of(account.get()))
                .get(account.get());
        return ref != null && ref.active() && PostingPurpose.BENEFITS.accepts(ref.type(), ref.holdsStudentBalances());
    }

    private static boolean isUsable(LedgerAccountDirectory.LedgerAccountRef account, String type,
            boolean holdsStudentBalances) {
        return account != null && account.active() && account.type().equals(type)
                && account.holdsStudentBalances() == holdsStudentBalances;
    }

    /** The preview of a calculation. */
    AccrualPreviewResult preview(UUID planId, LocalDate periodStart, Calculation calculation) {
        int created = 0;
        int existing = 0;
        int other = 0;
        int errors = 0;
        Map<AccrualErrorCode, Integer> byCode = new EnumMap<>(AccrualErrorCode.class);
        Map<CurrencyCode, long[]> counts = new TreeMap<>();
        Map<CurrencyCode, Money[]> sums = new TreeMap<>();
        List<AccrualPreviewResult.Item> items = new ArrayList<>();
        for (AccrualEngine.Line line : calculation.lines()) {
            Money base = null;
            Money benefit = null;
            Money netBase = null;
            Money tax = null;
            Money total = null;
            UUID awardId = null;
            String scholarshipCode = null;
            List<AppliedBenefit> applied = List.of();
            switch (line.outcome()) {
                case CREATED -> {
                    created++;
                    base = line.charge().amounts().base();
                    benefit = line.charge().amounts().discount();
                    netBase = line.charge().netBase();
                    tax = line.charge().amounts().tax();
                    total = line.charge().amounts().total();
                    applied = line.charge().benefits();
                    if (line.charge().benefit() != null) {
                        awardId = line.charge().benefit().awardId();
                        scholarshipCode = line.charge().benefit().scholarshipCode();
                    }
                    CurrencyCode currency = base.currency();
                    counts.computeIfAbsent(currency, c -> new long[1])[0]++;
                    Money[] sum = sums.computeIfAbsent(currency,
                            c -> new Money[] {Money.zero(c), Money.zero(c), Money.zero(c), Money.zero(c), Money.zero(c)});
                    sum[0] = sum[0].add(base);
                    sum[1] = sum[1].add(benefit);
                    sum[2] = sum[2].add(netBase);
                    sum[3] = sum[3].add(tax);
                    sum[4] = sum[4].add(total);
                }
                case ALREADY_ACCRUED -> existing++;
                case ALREADY_ACCRUED_OTHER_PLAN -> other++;
                case ERROR -> {
                    errors++;
                    byCode.merge(line.errorCode(), 1, Integer::sum);
                }
            }
            if (items.size() < AccrualPreviewResult.MAX_ITEMS) {
                items.add(new AccrualPreviewResult.Item(line.studentId(), line.paymentConceptId(), line.outcome(),
                        line.errorCode(), base, benefit, netBase, tax, total, awardId, scholarshipCode,
                        line.existingChargeId(), applied));
            }
        }
        List<AccrualPreviewResult.Total> totals = new ArrayList<>();
        for (Map.Entry<CurrencyCode, Money[]> entry : sums.entrySet()) {
            totals.add(new AccrualPreviewResult.Total(entry.getKey().name(), (int) counts.get(entry.getKey())[0],
                    entry.getValue()[0], entry.getValue()[1], entry.getValue()[2], entry.getValue()[3],
                    entry.getValue()[4]));
        }
        return new AccrualPreviewResult(planId, periodStart, calculation.population().size(), created, existing, other,
                errors, totals, byCode, items, calculation.lines().size() > AccrualPreviewResult.MAX_ITEMS,
                calculation.blockers(), calculation.digest());
    }

    /**
     * What each benefit of a charge adds to the digest: its source (the award, or the discount and the assignment), and its amount, so a
     * discount or an assignment that changes between the preview and the confirmation changes the digest and nothing is generated.
     */
    private static String benefitsOf(List<AppliedBenefit> benefits) {
        StringBuilder text = new StringBuilder();
        for (AppliedBenefit benefit : benefits) {
            text.append(benefit.source()).append(':').append(benefit.awardId()).append(':').append(benefit.discountId())
                    .append(':').append(benefit.assignmentId()).append(':').append(benefit.amount().toPlainString()).append(';');
        }
        return text.toString();
    }

    /** The digest of every line: what would be created, with its price and its tax validity, what exists and what fails. */
    static String digestOf(List<AccrualEngine.Line> lines) {
        StringBuilder text = new StringBuilder("v3");
        lines.stream().sorted(Comparator.comparing(AccrualEngine.Line::studentId)
                .thenComparing(AccrualEngine.Line::paymentConceptId)).forEach(line -> {
                    text.append('\n').append(line.studentId()).append('|').append(line.paymentConceptId()).append('|')
                            .append(line.outcome()).append('|').append(line.errorCode()).append('|')
                            .append(line.existingChargeId());
                    if (line.charge() != null) {
                        text.append('|').append(line.charge().priceListItemId()).append('|')
                                .append(line.charge().taxRatePeriodId()).append('|')
                                .append(line.charge().amounts().base().toPlainString()).append('|')
                                .append(line.charge().amounts().discount().toPlainString()).append('|')
                                .append(benefitsOf(line.charge().benefits()))
                                .append('|')
                                .append(line.charge().amounts().tax().toPlainString()).append('|')
                                .append(line.charge().amounts().total().toPlainString()).append('|')
                                .append(line.charge().period().start());
                    }
                });
        return HexFormat.of().formatHex(Digests.sha256(text.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
