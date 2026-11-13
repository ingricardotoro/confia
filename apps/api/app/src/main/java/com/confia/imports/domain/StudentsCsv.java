package com.confia.imports.domain;

import java.util.List;

/**
 * The template of the student import: the header it must have and the rows read under it. The header is checked by
 * name, in any order: {@code legacyId}, {@code firstName}, {@code lastName} and {@code birthDate} are required, {@code
 * nationalId} and {@code sex} are optional, and every other name is refused. The reading itself is that of {@link
 * CsvTemplate}, shared by every kind of import; the names of the header are never echoed back.
 *
 * <p>This only reads the shape. The rules of each value (length, dates, document) belong to {@code students}, which
 * judges the cells through its published port.
 */
public final class StudentsCsv {

    /** The columns the file must have. */
    public static final List<String> REQUIRED = List.of("legacyId", "firstName", "lastName", "birthDate");
    /** The columns the file may have. */
    public static final List<String> OPTIONAL = List.of("nationalId", "sex");

    /** The first line of the empty template a person downloads. */
    public static final String TEMPLATE_HEADER = "legacyId,firstName,lastName,birthDate,nationalId,sex";

    static final CsvTemplate TEMPLATE = new CsvTemplate(REQUIRED, OPTIONAL);

    /** One data row: the line where it starts and its cells, untrimmed; absent optional columns are null. */
    public record Row(int line, String legacyId, String firstName, String lastName, String birthDate, String nationalId,
            String sex) {

        /** Cells are personal data of a minor; only the line is shown. */
        @Override
        public String toString() {
            return "Row[line=" + line + "]";
        }
    }

    /**
     * The result of reading a file: every data row counted in {@code totalRows}, the rows that have the shape of the
     * header, and a rejection for each that does not.
     */
    public record Parsed(int totalRows, List<Row> rows, List<Rejection> shapeRejections) {

        public Parsed {
            rows = List.copyOf(rows);
            shapeRejections = List.copyOf(shapeRejections);
        }
    }

    private StudentsCsv() {
    }

    /**
     * Reads the text of a file. Refuses the file as a whole (an {@link InvalidImportFileException}) when it is empty,
     * has only the header, is malformed, has more than {@code maxRows} data rows, or has a wrong header.
     */
    public static Parsed parse(String text, int maxRows) {
        CsvTemplate.Parsed parsed = TEMPLATE.parse(text, maxRows);
        List<Row> rows = parsed.rows().stream()
                .map(row -> new Row(row.line(), row.cell("legacyId"), row.cell("firstName"), row.cell("lastName"),
                        row.cell("birthDate"), row.cell("nationalId"), row.cell("sex")))
                .toList();
        return new Parsed(parsed.totalRows(), rows, parsed.shapeRejections());
    }
}
