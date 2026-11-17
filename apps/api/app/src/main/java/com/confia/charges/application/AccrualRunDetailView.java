package com.confia.charges.application;

/** A run with one page of its items. */
public record AccrualRunDetailView(AccrualRunView run, Page<RunItem> items) {
}
