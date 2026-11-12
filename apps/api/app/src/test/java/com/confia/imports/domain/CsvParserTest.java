package com.confia.imports.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.imports.domain.CsvParser.CsvRecord;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The strict CSV reader: separators, quotes, line breaks inside a cell, line numbers and the limits. */
class CsvParserTest {

    private static List<CsvRecord> parse(String text) {
        return CsvParser.parse(text, 100);
    }

    private static List<String> cells(CsvRecord record) {
        return record.cells();
    }

    @Test
    void readsCommaSeparatedRecordsWithTheirLines() {
        List<CsvRecord> records = parse("a,b,c\n1,2,3\n4,5,6\n");

        assertThat(records).hasSize(3);
        assertThat(cells(records.get(1))).containsExactly("1", "2", "3");
        assertThat(records).extracting(CsvRecord::line).containsExactly(1, 2, 3);
    }

    @Test
    void detectsTheSemicolonFromTheFirstLine() {
        List<CsvRecord> records = parse("a;b;c\n1;2,5;3\n");

        assertThat(cells(records.get(1))).containsExactly("1", "2,5", "3");
    }

    @Test
    void aCommaInsideQuotesDoesNotCountWhenDetectingTheSeparator() {
        List<CsvRecord> records = parse("\"a,b,c\";d\n1;2\n");

        assertThat(cells(records.getFirst())).containsExactly("a,b,c", "d");
        assertThat(cells(records.get(1))).containsExactly("1", "2");
    }

    @Test
    void aTieOrNoSeparatorMeansComma() {
        assertThat(cells(parse("a,b;c\n").getFirst())).containsExactly("a", "b;c");
        assertThat(cells(parse("solo\n").getFirst())).containsExactly("solo");
    }

    @Test
    void aQuotedCellKeepsCommasQuotesAndLineBreaksAndTheLinesAfterItAreCountedRight() {
        String text = "a,b\n\"uno, \"\"dos\"\"\",\"tres\ncuatro\"\nx,y\n";

        List<CsvRecord> records = parse(text);

        assertThat(cells(records.get(1))).containsExactly("uno, \"dos\"", "tres\ncuatro");
        assertThat(records.get(1).line()).isEqualTo(2);
        assertThat(records.get(2).line()).as("the record after a cell with a line break").isEqualTo(4);
    }

    @Test
    void aCarriageReturnAndLineFeedInsideACellIsOneLineBreakAndOneLine() {
        List<CsvRecord> records = parse("a,b\r\n\"x\r\ny\",z\r\nlast,row\r\n");

        assertThat(cells(records.get(1))).containsExactly("x\ny", "z");
        assertThat(records.get(2).line()).isEqualTo(4);
    }

    @Test
    void acceptsAllThreeLineEndings() {
        assertThat(parse("a\r\nb\rc\nd")).extracting(r -> cells(r).getFirst()).containsExactly("a", "b", "c", "d");
        assertThat(parse("a\r\nb\rc\nd")).extracting(CsvRecord::line).containsExactly(1, 2, 3, 4);
    }

    @Test
    void skipsBlankLinesButStillCountsThem() {
        List<CsvRecord> records = parse("a,b\n\n1,2\n\n\n3,4\n\n");

        assertThat(records).extracting(CsvRecord::line).containsExactly(1, 3, 6);
    }

    @Test
    void aQuotedEmptyCellIsNotABlankLine() {
        List<CsvRecord> records = parse("a\n\"\"\n");

        assertThat(records).hasSize(2);
        assertThat(cells(records.get(1))).containsExactly("");
    }

    @Test
    void keepsEmptyCellsIncludingTheLastOne() {
        assertThat(cells(parse("a,,c,\n").getFirst())).containsExactly("a", "", "c", "");
        assertThat(cells(parse("a,b,").getFirst())).containsExactly("a", "b", "");
    }

    @Test
    void doesNotTrimCells() {
        assertThat(cells(parse(" a , b \n").getFirst())).containsExactly(" a ", " b ");
    }

    @Test
    void neverInterpretsACellThatLooksLikeAFormula() {
        List<CsvRecord> records = parse("a\n\"=HYPERLINK(\"\"http://x\"\")\"\n+1\n-2\n@SUM(A1)\n");

        assertThat(records).extracting(r -> cells(r).getFirst())
                .containsExactly("a", "=HYPERLINK(\"http://x\")", "+1", "-2", "@SUM(A1)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a,b\"c\n", "a,\"b\n", "\"a\"x,b\n", "a,\"b\"\"\n", "a, \"b\"\n"})
    void aMalformedFileIsRefusedWithoutEchoingIt(String text) {
        assertThatThrownBy(() -> parse(text)).isInstanceOfSatisfying(InvalidImportFileException.class,
                e -> assertThat(e.problems()).containsExactly(
                        new InvalidImportFileException.Problem("file", "malformed-csv")))
                .hasMessageNotContaining("b");
    }

    @Test
    void moreRecordsThanTheLimitRefuseTheFile() {
        StringBuilder text = new StringBuilder("h\n");
        for (int i = 0; i < 5; i++) {
            text.append(i).append('\n');
        }

        assertThat(CsvParser.parse(text.toString(), 6)).hasSize(6);
        assertThatThrownBy(() -> CsvParser.parse(text.toString(), 5)).isInstanceOfSatisfying(
                InvalidImportFileException.class,
                e -> assertThat(e.problems()).containsExactly(
                        new InvalidImportFileException.Problem("file", "too-many-rows")));
    }

    @Test
    void anEmptyTextHasNoRecords() {
        assertThat(parse("")).isEmpty();
        assertThat(parse("\n\n")).isEmpty();
    }

    @Test
    void theTextOfARecordShowsOnlyItsLine() {
        assertThat(parse("secreto\n").getFirst().toString()).isEqualTo("CsvRecord[line=1]");
    }
}
