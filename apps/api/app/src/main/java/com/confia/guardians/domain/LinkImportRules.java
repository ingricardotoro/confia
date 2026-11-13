package com.confia.guardians.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Judges the rows of a bulk import of links (docs/cortes/imports-guardians.md) as the sets of links they are: the rows
 * are <b>grouped by student</b> and each group is the complete set that the hand registration demands, so it is judged
 * with the very same rule, {@link ResponsibilitySplit} (INV-32: exact decimal sum of 100.00, at most one primary
 * payer, at most one billing recipient, no guardian twice, each percentage between 0.01 and 100 with two decimals
 * through {@link LinkDraft}). A group is imported whole or not at all: a defect in one of its rows rejects every line
 * of the group.
 *
 * <p>The facts of the registry (which references exist, whether the student and the guardians are active, whether
 * the student already has current links) are handed in as maps; this class reads nothing. The answer is a list of
 * problems, each a line, a column and a stable reason, never a value. The order in which a group is judged:
 *
 * <ol>
 *   <li>the cells of each row ({@code required}, {@code invalid-format}, {@code too-long}, {@code invalid-percent})
 *       and its references ({@code unknown-student}, {@code unknown-guardian}); if any row of the group has a problem,
 *       the others get {@code group-rejected} on the field {@code row};
 *   <li>the rules of the set: {@code duplicate-guardian}, {@code multiple-primary-payer}, {@code
 *       multiple-billing-recipient}, {@code sum-not-100}, on all the lines of the group;
 *   <li>{@code student-not-active}, and {@code guardian-not-active} on the lines of the inactive guardians (the others
 *       {@code group-rejected});
 *   <li>{@code student-already-linked}: the student has current links, made by hand or imported, and the import never
 *       replaces them. It is the idempotent answer to applying the same file twice.
 * </ol>
 */
public final class LinkImportRules {

    public static final String UNKNOWN_STUDENT = "unknown-student";
    public static final String UNKNOWN_GUARDIAN = "unknown-guardian";
    public static final String STUDENT_NOT_ACTIVE = "student-not-active";
    public static final String GUARDIAN_NOT_ACTIVE = "guardian-not-active";
    public static final String STUDENT_ALREADY_LINKED = "student-already-linked";
    public static final String INVALID_PERCENT = "invalid-percent";
    public static final String GROUP_REJECTED = "group-rejected";

    private static final Pattern PERCENT_SHAPE = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,2})?");
    private static final UUID PLACEHOLDER = new UUID(0L, 0L);

    /**
     * One data row as the raw text of its cells; {@code line} is where the row starts. A flag cell may be null (absent
     * column) or blank, which is {@code false}. Every cell is personal data by association, so the text form shows
     * only the line.
     */
    public record Row(int line, String studentLegacyId, String guardianLegacyId, String relationship, String percent,
            String primaryPayer, String billingRecipient) {

        @Override
        public String toString() {
            return "Row[line=" + line + "]";
        }
    }

    /** A line, a column and a stable reason; never the value. */
    public record Problem(int line, String field, String reason) {
    }

    /** What the registry says about a referenced student. */
    public record StudentFacts(UUID studentId, boolean active, boolean linked) {
    }

    /** What the registry says about a referenced guardian. */
    public record GuardianFacts(UUID guardianId, boolean active) {
    }

    private LinkImportRules() {
    }

    /** The references of the students that the rows name with a usable cell (stripped), to be looked up. */
    public static Set<String> studentReferences(List<Row> rows) {
        Set<String> references = new LinkedHashSet<>();
        for (Row row : rows) {
            String reference = reference(row.studentLegacyId());
            if (reference != null) {
                references.add(reference);
            }
        }
        return references;
    }

    /** The references of the guardians that the rows name with a usable cell (stripped), to be looked up. */
    public static Set<String> guardianReferences(List<Row> rows) {
        Set<String> references = new LinkedHashSet<>();
        for (Row row : rows) {
            String reference = reference(row.guardianLegacyId());
            if (reference != null) {
                references.add(reference);
            }
        }
        return references;
    }

    /**
     * The rows grouped by the reference of their student, in the order of the file. Only for rows that were judged
     * without problems, so every row has a usable student reference.
     */
    public static Map<String, List<Row>> groupsOf(List<Row> rows) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            groups.computeIfAbsent(row.studentLegacyId().strip(), key -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    /** The percentage of a row without problems, exact decimal. */
    public static BigDecimal percentOf(Row row) {
        return new BigDecimal(row.percent().strip());
    }

    /** The relationship of a row without problems, as the registry spells it. */
    public static String relationshipOf(Row row) {
        return row.relationship().strip();
    }

    /** The primary payer flag of a row without problems: {@code true} (any case), else {@code false}. */
    public static boolean primaryPayerOf(Row row) {
        return flag(row.primaryPayer());
    }

    /** The billing recipient flag of a row without problems: {@code true} (any case), else {@code false}. */
    public static boolean billingRecipientOf(Row row) {
        return flag(row.billingRecipient());
    }

    /**
     * Judges the rows. {@code students} and {@code guardians} hold what the registry knows, by stripped reference; a
     * reference that is absent does not exist in the institution. The problems come in line order.
     */
    public static List<Problem> judge(List<Row> rows, Map<String, StudentFacts> students,
            Map<String, GuardianFacts> guardians) {
        List<Problem> problems = new ArrayList<>();
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        Set<Integer> defective = new LinkedHashSet<>();
        for (Row row : rows) {
            List<Problem> own = cellProblems(row);
            String student = reference(row.studentLegacyId());
            String guardian = reference(row.guardianLegacyId());
            if (student != null && !students.containsKey(student)) {
                own.add(new Problem(row.line(), "studentLegacyId", UNKNOWN_STUDENT));
            }
            if (guardian != null && !guardians.containsKey(guardian)) {
                own.add(new Problem(row.line(), "guardianLegacyId", UNKNOWN_GUARDIAN));
            }
            if (!own.isEmpty()) {
                defective.add(row.line());
                problems.addAll(own);
            }
            if (student != null) {
                groups.computeIfAbsent(student, key -> new ArrayList<>()).add(row);
            }
        }
        for (Map.Entry<String, List<Row>> group : groups.entrySet()) {
            judgeGroup(students.get(group.getKey()), group.getValue(), defective, guardians, problems);
        }
        problems.sort(Comparator.comparingInt(Problem::line));
        return problems;
    }

    private static void judgeGroup(StudentFacts student, List<Row> rows, Set<Integer> defective,
            Map<String, GuardianFacts> guardians, List<Problem> problems) {
        if (rows.stream().anyMatch(row -> defective.contains(row.line()))) {
            rows.stream().filter(row -> !defective.contains(row.line()))
                    .forEach(row -> problems.add(new Problem(row.line(), "row", GROUP_REJECTED)));
            return;
        }
        List<LinkDraft> drafts = new ArrayList<>();
        for (Row row : rows) {
            drafts.add(new LinkDraft(guardians.get(row.guardianLegacyId().strip()).guardianId(),
                    Relationship.parse(relationshipOf(row)), percentOf(row), primaryPayerOf(row),
                    billingRecipientOf(row)));
        }
        try {
            ResponsibilitySplit.of(drafts);
        } catch (InvalidResponsibilitySplitException broken) {
            rows.forEach(row -> problems.add(new Problem(row.line(), fieldOf(broken.reason()), broken.reason())));
            return;
        }
        if (!student.active()) {
            rows.forEach(row -> problems.add(new Problem(row.line(), "studentLegacyId", STUDENT_NOT_ACTIVE)));
            return;
        }
        Set<Integer> inactive = new LinkedHashSet<>();
        for (Row row : rows) {
            if (!guardians.get(row.guardianLegacyId().strip()).active()) {
                inactive.add(row.line());
            }
        }
        if (!inactive.isEmpty()) {
            for (Row row : rows) {
                problems.add(inactive.contains(row.line())
                        ? new Problem(row.line(), "guardianLegacyId", GUARDIAN_NOT_ACTIVE)
                        : new Problem(row.line(), "row", GROUP_REJECTED));
            }
            return;
        }
        if (student.linked()) {
            rows.forEach(row -> problems.add(new Problem(row.line(), "studentLegacyId", STUDENT_ALREADY_LINKED)));
        }
    }

    /** The column a rule of the set is about. */
    private static String fieldOf(String reason) {
        return switch (reason) {
            case InvalidResponsibilitySplitException.DUPLICATE_GUARDIAN -> "guardianLegacyId";
            case InvalidResponsibilitySplitException.MULTIPLE_PRIMARY_PAYER -> "isPrimaryPayer";
            case InvalidResponsibilitySplitException.MULTIPLE_BILLING_RECIPIENT -> "isBillingRecipient";
            default -> "financialResponsibilityPercent";
        };
    }

    /** The problems of the cells of one row, before the registry is asked about its references. */
    private static List<Problem> cellProblems(Row row) {
        List<Problem> problems = new ArrayList<>();
        reference("studentLegacyId", row.studentLegacyId(), row.line(), problems);
        reference("guardianLegacyId", row.guardianLegacyId(), row.line(), problems);
        relationship(row, problems);
        percent(row, problems);
        flagCell("isPrimaryPayer", row.primaryPayer(), row.line(), problems);
        flagCell("isBillingRecipient", row.billingRecipient(), row.line(), problems);
        return problems;
    }

    private static void reference(String field, String value, int line, List<Problem> problems) {
        if (isBlank(value)) {
            problems.add(new Problem(line, field, GuardianFieldCheck.REQUIRED));
        } else if (hasControl(value)) {
            problems.add(new Problem(line, field, GuardianFieldCheck.INVALID_FORMAT));
        } else if (value.strip().length() > GuardianFieldCheck.MAX_EXTERNAL_REF_LENGTH) {
            problems.add(new Problem(line, field, GuardianFieldCheck.TOO_LONG));
        }
    }

    private static void relationship(Row row, List<Problem> problems) {
        String value = row.relationship();
        if (isBlank(value)) {
            problems.add(new Problem(row.line(), "relationship", GuardianFieldCheck.REQUIRED));
            return;
        }
        try {
            Relationship.parse(value.strip());
        } catch (InvalidGuardianException e) {
            problems.add(new Problem(row.line(), "relationship", GuardianFieldCheck.INVALID_FORMAT));
        }
    }

    private static void percent(Row row, List<Problem> problems) {
        String value = row.percent();
        if (isBlank(value)) {
            problems.add(new Problem(row.line(), "financialResponsibilityPercent", GuardianFieldCheck.REQUIRED));
            return;
        }
        String text = value.strip();
        boolean valid = PERCENT_SHAPE.matcher(text).matches();
        if (valid) {
            try {
                new LinkDraft(PLACEHOLDER, Relationship.OTHER, new BigDecimal(text), false, false);
            } catch (InvalidGuardianException e) {
                valid = false;
            }
        }
        if (!valid) {
            problems.add(new Problem(row.line(), "financialResponsibilityPercent", INVALID_PERCENT));
        }
    }

    private static void flagCell(String field, String value, int line, List<Problem> problems) {
        if (!isBlank(value) && !"true".equalsIgnoreCase(value.strip()) && !"false".equalsIgnoreCase(value.strip())) {
            problems.add(new Problem(line, field, GuardianFieldCheck.INVALID_FORMAT));
        }
    }

    private static boolean flag(String value) {
        return !isBlank(value) && "true".equalsIgnoreCase(value.strip());
    }

    /** The stripped reference of a cell that is usable (not blank, no control, not too long), or null. */
    private static String reference(String value) {
        if (isBlank(value) || hasControl(value) || value.strip().length() > GuardianFieldCheck.MAX_EXTERNAL_REF_LENGTH) {
            return null;
        }
        return value.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
