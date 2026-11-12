package com.confia.imports.application;

/** A rejected row or field: the line, the column and the stable reason; never the value. */
public record RejectionView(int line, String field, String reason) {
}
