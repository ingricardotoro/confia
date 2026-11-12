package com.confia.imports.domain;

import com.confia.imports.domain.CsvParser.CsvRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The template of the student import: the header it must have and the rows read under it. The header is checked by
 * name, in any order: {@code legacyId}, {@code firstName}, {@code lastName} and {@code birthDate} are required, {@code
 * nationalId} and {@code sex} are optional, and every other name is refused. The names of the header are never echoed
 * back (a file without a header would put a student in it): a column that is not part of the template is reported by
 * its position. A row with a different number of cells than the header is a rejection of that row, not of the file.
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

    private static final Set<String> KNOWN = Set.copyOf(
            java.util.stream.Stream.concat(REQUIRED.stream(), OPTIONAL.stream()).toList());

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
        if (text == null || text.isBlank()) {
            throw new InvalidImportFileException("file", "empty-file");
        }
        List<CsvRecord> records = CsvParser.parse(text, maxRows + 1);
        if (records.isEmpty()) {
            throw new InvalidImportFileException("file", "empty-file");
        }
        Map<String, Integer> columns = columnsOf(records.getFirst());
        if (records.size() == 1) {
            throw new InvalidImportFileException("file", "no-rows");
        }
        int width = records.getFirst().cells().size();
        List<Row> rows = new ArrayList<>();
        List<Rejection> shape = new ArrayList<>();
        for (CsvRecord record : records.subList(1, records.size())) {
            if (record.cells().size() != width) {
                shape.add(new Rejection(record.line(), "row", "invalid-format"));
                continue;
            }
            rows.add(new Row(record.line(), cell(record, columns, "legacyId"), cell(record, columns, "firstName"),
                    cell(record, columns, "lastName"), cell(record, columns, "birthDate"),
                    cell(record, columns, "nationalId"), cell(record, columns, "sex")));
        }
        return new Parsed(records.size() - 1, rows, shape);
    }

    private static String cell(CsvRecord record, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null ? null : record.cells().get(index);
    }

    private static Map<String, Integer> columnsOf(CsvRecord header) {
        Map<String, Integer> columns = new HashMap<>();
        List<InvalidImportFileException.Problem> problems = new ArrayList<>();
        List<String> names = header.cells();
        if (names.size() > CsvParser.MAX_CELLS) {
            throw new InvalidImportFileException("file", "too-many-columns");
        }
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i).strip();
            if (!KNOWN.contains(name) || columns.putIfAbsent(name, i) != null) {
                problems.add(new InvalidImportFileException.Problem("columns[" + i + "]", "unknown-column"));
            }
        }
        for (String required : REQUIRED) {
            if (!columns.containsKey(required)) {
                problems.add(new InvalidImportFileException.Problem(required, "missing-column"));
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidImportFileException(problems);
        }
        return columns;
    }
}
