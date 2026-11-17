package com.confia.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An account of the chart of an institution (docs/cortes/ledger-chart-of-accounts.md). The chart is DATA loaded by the Super
 * Administrator: nothing of it is code. Its code, its type and its student flag never change (the database refuses it too);
 * only the name and the active flag do, with the version that goes with them. An account is never deleted, it is
 * deactivated, and a deactivated account only refuses NEW facts: the entries it already has stay readable and reversible.
 *
 * @param version the optimistic-lock version, from 0, moved by every edit
 */
public record LedgerAccount(UUID id, String code, String name, AccountType type, boolean holdsStudentBalances,
        boolean active, long version, Instant createdAt, Instant updatedAt) {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,29}$");
    private static final Pattern FORBIDDEN_IN_NAME = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    static final int NAME_MAX = 120;

    public LedgerAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (code == null || !CODE.matcher(code).matches() || version < 0
                || (holdsStudentBalances && !type.canHoldStudentBalances())) {
            throw new InvalidLedgerAccountException();
        }
        name = cleanName(name);
    }

    /** A new account: active, at version 0. */
    public static LedgerAccount create(UUID id, String code, String name, AccountType type,
            boolean holdsStudentBalances, Instant now) {
        return new LedgerAccount(id, code == null ? null : code.strip(), name, type, holdsStudentBalances, true, 0L, now,
                now);
    }

    /**
     * The same account with a new name and active flag. {@code expectedVersion} is the one the editor read: any other
     * answers a stale-version error and nothing changes. The version moves by one on every accepted edit, even if the
     * values are the same, so two editors never both think they won.
     */
    public LedgerAccount edit(long expectedVersion, String newName, boolean newActive, Instant now) {
        if (expectedVersion != version) {
            throw new StaleAccountVersionException();
        }
        return new LedgerAccount(id, code, newName, type, holdsStudentBalances, newActive, version + 1, createdAt, now);
    }

    /** Whether this edit turns an active account off. */
    public boolean deactivates(boolean newActive) {
        return active && !newActive;
    }

    private static String cleanName(String raw) {
        if (raw == null) {
            throw new InvalidLedgerAccountException();
        }
        String name = raw.strip();
        if (name.isEmpty() || name.length() > NAME_MAX || FORBIDDEN_IN_NAME.matcher(name).find()) {
            throw new InvalidLedgerAccountException();
        }
        return name;
    }
}
