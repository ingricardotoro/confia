package com.confia.identity.application;

/**
 * What one batch of the refresh-token purge removed (background-jobs-with-db-scheduler design.md
 * decision 17): the families, which decide whether the batch was full, and all the tokens of those
 * families.
 *
 * @param families the families deleted
 * @param tokens the tokens of those families deleted with them
 */
public record RefreshTokenPurgeCount(long families, long tokens) {

    public RefreshTokenPurgeCount {
        if (families < 0 || tokens < 0) {
            throw new IllegalArgumentException("a purge count is never negative");
        }
    }
}
