package com.confia.shared.jobs;

/**
 * The kind of actor that caused a job to be scheduled, carried in {@link JobMeta} for the audit
 * trail (background-jobs-with-db-scheduler design.md decision 6). It is never the identity a
 * handler runs as: every handler runs as the {@code system} actor ({@link
 * JobContext#systemContext()}), and the kind recorded here only says who asked.
 */
public enum ActorKind {
    STAFF,
    GUARDIAN,
    SYSTEM
}
