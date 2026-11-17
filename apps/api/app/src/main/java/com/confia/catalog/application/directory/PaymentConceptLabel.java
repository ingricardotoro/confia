package com.confia.catalog.application.directory;

import java.util.Objects;
import java.util.UUID;

/**
 * How a payment concept is named on a document that shows it (for example a statement): its code and its name. A
 * deactivated concept still has a label, because the charges it produced stay in the history.
 */
public record PaymentConceptLabel(UUID id, String code, String name) {

    public PaymentConceptLabel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
    }
}
