package com.confia.imports.domain;

import com.confia.imports.domain.CsvParser.CsvRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The template of one kind of import: the columns its header must have and the rows read under it. The header is
 * checked by name, in any order: the required columns must be there, the optional ones may be, and every other name,
 * or a repeated one, is refused. The names of the header are never echoed back (a file without a header would put a
 * person in it): a column that is not part of the template is reported by its position. A row with a different number
 * of cells than the header is a rejection of that row, not of the file.
 *
 * <p>This only reads the shape. The rules of each value belong to the module that owns the data, which judges the
 * cells through its published port.
 */
public final class CsvTemplate {

    private final List<String> required;
    private final Set<String> known;
    private final String header;

    /** The columns the file must have and the columns it may have; the header line is both, joined by commas. */
    public CsvTemplate(List<String> required, List<String> optional) {
        this.required = List.copyOf(required);
        Set<String> names = new LinkedHashSet<>(required);
        names.addAll(optional);
        this.known = Set.copyOf(names);
        this.header = String.join(",", names);
    }

    /** One data row: the line where it starts and its cells, untrimmed, by column name. */
    public record Row(int line, Map<String, String> cells) {

        public Row {
            cells = Map.copyOf(cells);
        }

        /** The cell of a column, or {@code null} when the file has no such column. */
        public String cell(String column) {
            return cells.get(column);
        }

        /** Cells are personal data; only the line is shown. */
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

    /** The first line of the empty template a person downloads. */
    public String header() {
        return header;
    }

    /**
     * Reads the text of a file. Refuses the file as a whole (an {@link InvalidImportFileException}) when it is empty,
     * has only the header, is malformed, has more than {@code maxRows} data rows, or has a wrong header.
     */
    public Parsed parse(String text, int maxRows) {
        if (text == null || text.isBlank()) {
            throw new InvalidImportFileException("file", "empty-file");
        }
        List<CsvRecord> records = CsvParser.parse(text, maxRows + 1);
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
            Map<String, String> cells = new HashMap<>();
            columns.forEach((name, index) -> cells.put(name, record.cells().get(index)));
            rows.add(new Row(record.line(), cells));
        }
        return new Parsed(records.size() - 1, rows, shape);
    }

    private Map<String, Integer> columnsOf(CsvRecord header) {
        Map<String, Integer> columns = new HashMap<>();
        List<InvalidImportFileException.Problem> problems = new ArrayList<>();
        List<String> names = header.cells();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i).strip();
            if (!known.contains(name) || columns.putIfAbsent(name, i) != null) {
                problems.add(new InvalidImportFileException.Problem("columns[" + i + "]", "unknown-column"));
            }
        }
        for (String column : required) {
            if (!columns.containsKey(column)) {
                problems.add(new InvalidImportFileException.Problem(column, "missing-column"));
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidImportFileException(problems);
        }
        return columns;
    }
}
