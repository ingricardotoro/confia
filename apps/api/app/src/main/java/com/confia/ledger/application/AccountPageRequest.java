package com.confia.ledger.application;

import com.confia.ledger.domain.InvalidLedgerAccountException;
import java.util.Set;

/**
 * What a list of accounts asks for: a page counted from zero, a size of at most {@link #MAX_SIZE} (the server applies the
 * limit whatever the client sent) and one field of a closed set to sort by, so a field outside the set never reaches a query.
 */
public record AccountPageRequest(int page, int size, String sortField, boolean ascending) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;
    /** The fields a list of accounts may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("code", "name");

    public AccountPageRequest {
        if (page < 0 || size < 1 || size > MAX_SIZE || !SORT_FIELDS.contains(sortField)) {
            throw new InvalidLedgerAccountException();
        }
    }

    /** Reads the raw parameters: {@code sort} is {@code field} or {@code field,asc|desc}; none means code ascending. */
    public static AccountPageRequest of(int page, int size, String sort) {
        if (sort == null || sort.isBlank()) {
            return new AccountPageRequest(page, size, "code", true);
        }
        String[] parts = sort.split(",", -1);
        boolean ascending = true;
        if (parts.length > 2) {
            throw new InvalidLedgerAccountException();
        }
        if (parts.length == 2) {
            String direction = parts[1].strip();
            if (direction.equalsIgnoreCase("desc")) {
                ascending = false;
            } else if (!direction.equalsIgnoreCase("asc")) {
                throw new InvalidLedgerAccountException();
            }
        }
        return new AccountPageRequest(page, size, parts[0].strip(), ascending);
    }

    /** The offset of the first row, in {@code long} so a huge page cannot overflow. */
    public long offset() {
        return (long) page * size;
    }
}
