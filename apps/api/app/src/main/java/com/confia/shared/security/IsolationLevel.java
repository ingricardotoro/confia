package com.confia.shared.security;

/**
 * The two isolation levels {@link TransactionRunner} supports (ADR-0010): {@code READ_COMMITTED}
 * is the default for every use case; {@code SERIALIZABLE} is reserved for the two operations
 * ADR-0010 names explicitly (cash-session closing and fiscal-document issuance).
 */
public enum IsolationLevel {
    READ_COMMITTED,
    SERIALIZABLE
}
