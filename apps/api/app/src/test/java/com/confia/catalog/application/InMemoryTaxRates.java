package com.confia.catalog.application;

import com.confia.catalog.domain.DuplicateTaxRateCodeException;
import com.confia.catalog.domain.ResourceNotFoundException;
import com.confia.catalog.domain.StaleVersionException;
import com.confia.catalog.domain.TaxRate;
import com.confia.kernel.InstitutionId;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An in-memory port that, like the database, rejects a code that differs only by capitals in the institution. It counts
 * the locks taken, so a test can prove the use case read the rate in the mode its rule needs.
 */
final class InMemoryTaxRates implements TaxRateRepository {

    record Key(InstitutionId institution, UUID id) {
    }

    final Map<Key, TaxRate> rows = new LinkedHashMap<>();
    int forUpdateLocks;
    int forShareLocks;

    TaxRate arrange(InstitutionId owner, String code, String name, boolean active) {
        TaxRate rate = new TaxRate(UUID.randomUUID(), code, name, active, 0,
                java.time.Instant.parse("2026-02-28T15:00:00Z"), java.time.Instant.parse("2026-02-28T15:00:00Z"));
        rows.put(new Key(owner, rate.id()), rate);
        return rate;
    }

    @Override
    public Optional<TaxRate> findById(InstitutionId institution, UUID id) {
        return Optional.ofNullable(rows.get(new Key(institution, id)));
    }

    @Override
    public Map<UUID, TaxRate> findByIds(InstitutionId institution, Collection<UUID> ids) {
        Map<UUID, TaxRate> found = new HashMap<>();
        for (UUID id : ids) {
            findById(institution, id).ifPresent(rate -> found.put(id, rate));
        }
        return found;
    }

    @Override
    public Optional<TaxRate> lockById(InstitutionId institution, UUID id) {
        forUpdateLocks++;
        return findById(institution, id);
    }

    @Override
    public Optional<TaxRate> lockShareById(InstitutionId institution, UUID id) {
        forShareLocks++;
        return findById(institution, id);
    }

    @Override
    public void insert(InstitutionId institution, TaxRate rate) {
        boolean taken = rows.entrySet().stream().anyMatch(entry -> entry.getKey().institution().equals(institution)
                && entry.getValue().code().equalsIgnoreCase(rate.code()));
        if (taken) {
            throw new DuplicateTaxRateCodeException();
        }
        rows.put(new Key(institution, rate.id()), rate);
    }

    @Override
    public void update(InstitutionId institution, TaxRate rate, long expectedVersion) {
        Key key = new Key(institution, rate.id());
        if (!rows.containsKey(key)) {
            throw new ResourceNotFoundException();
        }
        if (rows.get(key).version() != expectedVersion) {
            throw new StaleVersionException();
        }
        rows.put(key, rate);
    }

    @Override
    public Page<TaxRate> list(InstitutionId institution, PageRequest request, TaxRateFilter filter) {
        Comparator<TaxRate> order = request.sortField().equals("code")
                ? Comparator.comparing(rate -> rate.code().toLowerCase())
                : Comparator.comparing(TaxRate::name);
        List<TaxRate> matching = rows.entrySet().stream()
                .filter(entry -> entry.getKey().institution().equals(institution))
                .map(Map.Entry::getValue)
                .filter(rate -> filter.active() == null || rate.active() == filter.active())
                .sorted(request.ascending() ? order : order.reversed()).toList();
        int from = (int) Math.min(request.offset(), matching.size());
        int to = Math.min(from + request.size(), matching.size());
        return new Page<>(matching.subList(from, to), matching.size(), request.page(), request.size());
    }
}
