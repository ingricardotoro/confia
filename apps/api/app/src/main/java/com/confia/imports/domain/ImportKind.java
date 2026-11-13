package com.confia.imports.domain;

import java.util.List;

/**
 * What a batch loads: the students, the guardians (each with the reference it has in the source system) and the links
 * between guardians and students already imported. Enrollments come in a later cut. Each kind has the template of its
 * file ({@link CsvTemplate}) and the name it has in the routes ({@code students}, {@code guardians}, {@code
 * guardian-links}).
 */
public enum ImportKind {
    STUDENTS("students", StudentsCsv.TEMPLATE),
    GUARDIANS("guardians", new CsvTemplate(List.of("legacyId", "firstName", "lastName"),
            List.of("nationalId", "rtn", "email", "phone", "address", "preferredChannel"))),
    GUARDIAN_LINKS("guardian-links", new CsvTemplate(
            List.of("studentLegacyId", "guardianLegacyId", "relationship", "financialResponsibilityPercent"),
            List.of("isPrimaryPayer", "isBillingRecipient")));

    private final String route;
    private final CsvTemplate template;

    ImportKind(String route, CsvTemplate template) {
        this.route = route;
        this.template = template;
    }

    /** The name of the kind in the path of the routes and in the filter of the history. */
    public String route() {
        return route;
    }

    /** The template of the file of this kind. */
    public CsvTemplate template() {
        return template;
    }

    /** The kind with this name in the routes, or {@code null} when there is none. */
    public static ImportKind ofRoute(String route) {
        for (ImportKind kind : values()) {
            if (kind.route.equals(route)) {
                return kind;
            }
        }
        return null;
    }
}
