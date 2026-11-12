package com.confia.imports.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * A small, strict CSV reader (RFC 4180 with the two separators a Spanish-locale spreadsheet writes). It is strict on
 * purpose, so a file that is not what it looks like is refused instead of guessed at:
 *
 * <ul>
 *   <li>the separator is {@code ,} or {@code ;}, whichever is the more frequent outside quotes on the first line;</li>
 *   <li>a cell is quoted only when it starts with a quote; inside, {@code ""} is a quote and a line break is part of
 *       the cell; after the closing quote only a separator, a line end or the end of the file may follow;</li>
 *   <li>a quote inside an unquoted cell, or a quoted cell that never closes, makes the file malformed;</li>
 *   <li>{@code \r\n}, {@code \n} and {@code \r} end a record, and completely empty lines are skipped;</li>
 *   <li>more records than the limit refuse the file before the rest is read, and so do more than {@link
 *       #MAX_CELLS} cells in one record (checked as each cell is added, so a file of separators allocates nothing
 *       beyond the limit).</li>
 * </ul>
 *
 * <p>It only splits text. It never interprets a cell: a cell that starts with {@code =}, {@code +}, {@code -} or
 * {@code @} is text like any other and is never evaluated. The text it reads is personal data of minors, so a failure
 * names the reason and never an excerpt.
 */
public final class CsvParser {

    /** One record: the line of the file where it starts (the first line is 1) and its cells, untrimmed. */
    public record CsvRecord(int line, List<String> cells) {

        public CsvRecord {
            cells = List.copyOf(cells);
        }

        /** Cells are personal data; only the line is shown. */
        @Override
        public String toString() {
            return "CsvRecord[line=" + line + "]";
        }
    }

    /** The most cells a record may have; the template has six columns, so this is a generous bound, not a feature. */
    public static final int MAX_CELLS = 32;

    private static final String FILE = "file";

    private final String text;
    private final char delimiter;
    private int position;
    private int line = 1;
    private boolean startedWithQuote;

    private CsvParser(String text) {
        this.text = text;
        this.delimiter = delimiterOf(text);
    }

    /**
     * The records of {@code text}, the header included. More than {@code maxRecords} records refuse the file with
     * {@code too-many-rows}; a malformed file with {@code malformed-csv}.
     */
    public static List<CsvRecord> parse(String text, int maxRecords) {
        return new CsvParser(text).records(maxRecords);
    }

    private List<CsvRecord> records(int maxRecords) {
        List<CsvRecord> records = new ArrayList<>();
        while (position < text.length()) {
            char first = text.charAt(position);
            if (first == '\r' || first == '\n') {
                // An empty line is skipped without building a record.
                endOfLine();
                continue;
            }
            int startLine = line;
            List<String> cells = record();
            if (cells.size() == 1 && cells.getFirst().isEmpty() && !startedWithQuote) {
                continue;
            }
            if (records.size() >= maxRecords) {
                throw new InvalidImportFileException(FILE, "too-many-rows");
            }
            records.add(new CsvRecord(startLine, cells));
        }
        return records;
    }

    private List<String> record() {
        List<String> cells = new ArrayList<>();
        startedWithQuote = false;
        while (true) {
            boolean quoted = position < text.length() && text.charAt(position) == '"';
            if (cells.isEmpty()) {
                startedWithQuote = quoted;
            }
            if (cells.size() >= MAX_CELLS) {
                throw new InvalidImportFileException(FILE, "too-many-columns");
            }
            cells.add(quoted ? quotedCell() : plainCell());
            if (position >= text.length()) {
                return cells;
            }
            char next = text.charAt(position);
            if (next == delimiter) {
                position++;
                if (position >= text.length()) {
                    if (cells.size() >= MAX_CELLS) {
                        throw new InvalidImportFileException(FILE, "too-many-columns");
                    }
                    cells.add("");
                    return cells;
                }
                continue;
            }
            endOfLine();
            return cells;
        }
    }

    private String plainCell() {
        int start = position;
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c == delimiter || c == '\r' || c == '\n') {
                break;
            }
            if (c == '"') {
                throw malformed();
            }
            position++;
        }
        return text.substring(start, position);
    }

    private String quotedCell() {
        position++;
        StringBuilder cell = new StringBuilder();
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c == '"') {
                if (position + 1 < text.length() && text.charAt(position + 1) == '"') {
                    cell.append('"');
                    position += 2;
                    continue;
                }
                position++;
                if (position < text.length()) {
                    char after = text.charAt(position);
                    if (after != delimiter && after != '\r' && after != '\n') {
                        throw malformed();
                    }
                }
                return cell.toString();
            }
            if (c == '\r') {
                // A line break inside a cell keeps the cell text as a single line feed, and counts one line.
                position += position + 1 < text.length() && text.charAt(position + 1) == '\n' ? 2 : 1;
                line++;
                cell.append('\n');
                continue;
            }
            if (c == '\n') {
                line++;
            }
            cell.append(c);
            position++;
        }
        throw malformed();
    }

    /** Consumes one line end ({@code \r\n}, {@code \n} or {@code \r}) and counts the line. */
    private void endOfLine() {
        char c = text.charAt(position);
        position += c == '\r' && position + 1 < text.length() && text.charAt(position + 1) == '\n' ? 2 : 1;
        line++;
    }

    private static InvalidImportFileException malformed() {
        return new InvalidImportFileException(FILE, "malformed-csv");
    }

    /** {@code ;} when it is more frequent than {@code ,} outside quotes on the first line, {@code ,} otherwise. */
    static char delimiterOf(String text) {
        int commas = 0;
        int semicolons = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '\r' || c == '\n') {
                    break;
                }
                if (c == ',') {
                    commas++;
                } else if (c == ';') {
                    semicolons++;
                }
            }
        }
        return semicolons > commas ? ';' : ',';
    }
}
