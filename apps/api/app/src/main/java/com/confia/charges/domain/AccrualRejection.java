package com.confia.charges.domain;

/**
 * Why a run was refused before it wrote anything, or why it failed: the {@code failure_code} of the run. A rejection is a
 * precondition that did not hold (nothing is written); {@link #RETRIES_EXHAUSTED} is the only failure.
 */
public enum AccrualRejection {
    PLAN_NOT_ACTIVE("plan-not-active"),
    PERIOD_NOT_IN_PLAN("period-not-in-plan"),
    POSTING_ACCOUNTS_INCOMPLETE("posting-accounts-incomplete"),
    PERIOD_IN_FUTURE("period-in-future"),
    PLAN_TARGET_OUTSIDE_YEAR("plan-target-outside-year"),
    PREVIEW_OUTDATED("preview-outdated"),
    RETRIES_EXHAUSTED("retries-exhausted");

    private final String code;

    AccrualRejection(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
