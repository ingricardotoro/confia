package com.confia.organization.domain;

/** The life of an academic year: planned, then active, then closed. A closed year never leaves CLOSED. */
public enum AcademicYearStatus {
    PLANNED, ACTIVE, CLOSED
}
