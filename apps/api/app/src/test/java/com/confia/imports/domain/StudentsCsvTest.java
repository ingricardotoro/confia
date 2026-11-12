package com.confia.imports.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.imports.domain.InvalidImportFileException.Problem;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The template of the student import: the header by name, the optional columns and the rows that do not fit. */
class StudentsCsvTest {

    private static final String HEADER = "legacyId,firstName,lastName,birthDate,nationalId,sex";

    private static List<Problem> refused(String text) {
        try {
            StudentsCsv.parse(text, 100);
        } catch (InvalidImportFileException e) {
            return e.problems();
        }
        throw new AssertionError("the file was accepted");
    }

    @Test
    void readsTheRowsUnderTheHeaderByName() {
        StudentsCsv.Parsed parsed = StudentsCsv.parse(HEADER + "\nL1,Ana,Perez,2012-05-20,0801-2012-01234,F\n", 100);

        assertThat(parsed.totalRows()).isEqualTo(1);
        assertThat(parsed.shapeRejections()).isEmpty();
        StudentsCsv.Row row = parsed.rows().getFirst();
        assertThat(row.line()).isEqualTo(2);
        assertThat(row.legacyId()).isEqualTo("L1");
        assertThat(row.firstName()).isEqualTo("Ana");
        assertThat(row.birthDate()).isEqualTo("2012-05-20");
        assertThat(row.nationalId()).isEqualTo("0801-2012-01234");
        assertThat(row.sex()).isEqualTo("F");
    }

    @Test
    void theOrderOfTheColumnsIsFree() {
        StudentsCsv.Parsed parsed = StudentsCsv.parse(
                "birthDate;lastName;legacyId;firstName\n2012-05-20;Perez;L1;Ana\n", 100);

        StudentsCsv.Row row = parsed.rows().getFirst();
        assertThat(row.legacyId()).isEqualTo("L1");
        assertThat(row.firstName()).isEqualTo("Ana");
        assertThat(row.lastName()).isEqualTo("Perez");
        assertThat(row.nationalId()).as("an absent optional column").isNull();
        assertThat(row.sex()).isNull();
    }

    @Test
    void theNamesOfTheHeaderAreStrippedButCaseSensitive() {
        assertThat(StudentsCsv.parse(" legacyId , firstName ,lastName,birthDate\nL1,Ana,Perez,2012-05-20\n", 100)
                .rows()).hasSize(1);
        assertThat(refused("LegacyId,firstName,lastName,birthDate\nL1,Ana,Perez,2012-05-20\n"))
                .contains(new Problem("legacyId", "missing-column"));
    }

    @Test
    void everyMissingRequiredColumnIsListedByName() {
        assertThat(refused("firstName,nationalId\nAna,\n")).containsExactlyInAnyOrder(
                new Problem("legacyId", "missing-column"), new Problem("lastName", "missing-column"),
                new Problem("birthDate", "missing-column"));
    }

    @Test
    void anUnknownOrRepeatedColumnIsReportedByPositionAndNeverByItsText() {
        List<Problem> problems = refused("legacyId,Wenceslao Marcador,lastName,birthDate,firstName,lastName\n"
                + "L1,x,y,2012-05-20,z,w\n");

        assertThat(problems).containsExactlyInAnyOrder(new Problem("columns[1]", "unknown-column"),
                new Problem("columns[5]", "unknown-column"));
        assertThat(problems.toString()).doesNotContain("Wenceslao");
    }

    @Test
    void aFileWithoutAHeaderIsRefusedWithoutEchoingTheFirstStudent() {
        List<Problem> problems = refused("L1,Wenceslao,Marcador,2012-05-20\nL2,Ana,Perez,2012-05-21\n");

        assertThat(problems).extracting(Problem::reason).contains("unknown-column", "missing-column");
        assertThat(problems.toString()).doesNotContain("Wenceslao").doesNotContain("Marcador");
    }

    @Test
    void anEmptyFileAndAFileWithOnlyBlanksAreRefused() {
        assertThat(refused("")).containsExactly(new Problem("file", "empty-file"));
        assertThat(refused("  \n\n \r\n")).containsExactly(new Problem("file", "empty-file"));
        assertThatThrownBy(() -> StudentsCsv.parse(null, 100)).isInstanceOf(InvalidImportFileException.class);
    }

    @Test
    void aFileWithOnlyTheHeaderHasNoRows() {
        assertThat(refused(HEADER + "\n")).containsExactly(new Problem("file", "no-rows"));
        assertThat(refused(HEADER)).containsExactly(new Problem("file", "no-rows"));
    }

    @Test
    void aRowWithAnotherNumberOfCellsIsRejectedOnItsOwnAndTheOthersStay() {
        StudentsCsv.Parsed parsed = StudentsCsv.parse(
                "legacyId,firstName,lastName,birthDate\nL1,Ana,Perez,2012-05-20\nL2,Luis,Lopez\n"
                        + "L3,Eva,Mejia,2012-05-22,extra\nL4,Rosa,Diaz,2012-05-23\n", 100);

        assertThat(parsed.totalRows()).isEqualTo(4);
        assertThat(parsed.rows()).extracting(StudentsCsv.Row::legacyId).containsExactly("L1", "L4");
        assertThat(parsed.shapeRejections()).containsExactly(new Rejection(3, "row", "invalid-format"),
                new Rejection(4, "row", "invalid-format"));
    }

    @Test
    void theRowLimitCountsDataRowsOnlyAndTheHeaderIsFree() {
        StringBuilder text = new StringBuilder("legacyId,firstName,lastName,birthDate\n");
        for (int i = 1; i <= 3; i++) {
            text.append("L").append(i).append(",Ana,Perez,2012-05-20\n");
        }

        assertThat(StudentsCsv.parse(text.toString(), 3).totalRows()).isEqualTo(3);
        assertThat(catchProblems(text.toString(), 2)).containsExactly(new Problem("file", "too-many-rows"));
    }

    private static List<Problem> catchProblems(String text, int maxRows) {
        try {
            StudentsCsv.parse(text, maxRows);
        } catch (InvalidImportFileException e) {
            return e.problems();
        }
        throw new AssertionError("the file was accepted");
    }

    @Test
    void theLinesOfTheRowsFollowTheBreaksInsideQuotedCells() {
        StudentsCsv.Parsed parsed = StudentsCsv.parse("legacyId,firstName,lastName,birthDate\n"
                + "L1,\"An\na\",Perez,2012-05-20\nL2,Luis,Lopez,2012-05-21\n", 100);

        assertThat(parsed.rows()).extracting(StudentsCsv.Row::line).containsExactly(2, 4);
        assertThat(parsed.rows().getFirst().firstName()).isEqualTo("An\na");
    }

    @Test
    void theTextOfARowShowsOnlyItsLine() {
        StudentsCsv.Parsed parsed = StudentsCsv.parse("legacyId,firstName,lastName,birthDate\nL1,Ana,Perez,x\n", 10);

        assertThat(parsed.rows().getFirst().toString()).isEqualTo("Row[line=2]");
    }

    @Test
    void aHeaderOfFiveMegabytesOfCommasIsRefusedWithASingleProblem() {
        assertThat(refused(",".repeat(5 * 1024 * 1024))).containsExactly(new Problem("file", "too-many-columns"));
    }

    @Test
    void aHeaderOverTheCellLimitIsRefusedWithoutListingEachColumn() {
        String header = HEADER + ",x".repeat(CsvParser.MAX_CELLS);

        assertThat(refused(header + "\n")).containsExactly(new Problem("file", "too-many-columns"));
    }

    @Test
    void aRowOverTheCellLimitRefusesTheFile() {
        assertThat(refused(HEADER + "\n" + ",".repeat(CsvParser.MAX_CELLS) + "\n"))
                .containsExactly(new Problem("file", "too-many-columns"));
    }

    @Test
    void fiveMegabytesOfRowsOfCommasAreRefusedByRowCountWithASingleProblem() {
        String text = HEADER + "\n" + ",\n".repeat(5 * 1024 * 1024 / 2);

        assertThat(refused(text)).containsExactly(new Problem("file", "too-many-rows"));
    }

    @Test
    void theProblemsOfAnExceptionAreBoundedWhateverTheCaller() {
        List<Problem> many = java.util.stream.IntStream.range(0, 100_000)
                .mapToObj(i -> new Problem("columns[" + i + "]", "unknown-column")).toList();

        assertThat(new InvalidImportFileException(many).problems())
                .hasSize(InvalidImportFileException.MAX_PROBLEMS).isEqualTo(many.subList(0,
                        InvalidImportFileException.MAX_PROBLEMS));
    }
}
