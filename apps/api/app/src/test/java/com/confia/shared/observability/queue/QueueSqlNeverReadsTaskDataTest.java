package com.confia.shared.observability.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.infrastructure.JooqScheduledTaskQueueReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Scenarios BJ33 and BI101 of background-jobs-with-db-scheduler (design.md decision 20): the statement of the
 * queue reader never names {@code task_data} and uses no column of {@code scheduled_tasks} but the five the metrics
 * need. The inspection is static, on the text of the statement of the real class, and it runs on permanent
 * fixtures that do select {@code task_data} to show it can fail. {@code select *} is refused too, because it
 * would read the column without naming it.
 */
class QueueSqlNeverReadsTaskDataTest {

    /** The only columns of {@code scheduled_tasks} the metrics may use. */
    private static final Set<String> ALLOWED_COLUMNS = Set.of("task_name", "execution_time", "picked",
            "last_heartbeat", "consecutive_failures");

    /** Every other column of the table the library owns (V10); the metrics must not touch them. */
    private static final Set<String> FORBIDDEN_COLUMNS = Set.of("task_instance", "task_data", "picked_by",
            "last_success", "last_failure", "version", "priority");

    private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'");
    private static final Pattern WORD = Pattern.compile("[a-z_][a-z0-9_]*");

    /** What a reader that selects {@code task_data} looks like; the inspection must reject it. */
    static final class TaskDataSelectingReader {
        static final String QUERY = """
                select task_name, task_data, execution_time, picked, last_heartbeat, consecutive_failures
                  from scheduled_tasks
                """;
    }

    /** What a reader that selects every column looks like; the inspection must reject it too. */
    static final class SelectStarReader {
        static final String QUERY = "select * from scheduled_tasks";
    }

    /** One line per problem found in {@code sql}; empty when the statement only uses the allowed columns. */
    static List<String> problems(String sql) {
        List<String> problems = new ArrayList<>();
        String code = STRING_LITERAL.matcher(sql.toLowerCase(Locale.ROOT)).replaceAll("''");
        Matcher words = WORD.matcher(code);
        Set<String> seen = new TreeSet<>();
        while (words.find()) {
            seen.add(words.group());
        }
        for (String column : FORBIDDEN_COLUMNS) {
            if (seen.contains(column)) {
                problems.add("the statement names the column " + column);
            }
        }
        if (code.replace("count(*)", "").contains("*")) {
            problems.add("the statement selects every column with *");
        }
        if (!seen.containsAll(ALLOWED_COLUMNS)) {
            Set<String> missing = new TreeSet<>(ALLOWED_COLUMNS);
            missing.removeAll(seen);
            problems.add("the statement is not the one under inspection: it never uses " + missing);
        }
        return problems;
    }

    @Test
    void theRealStatementNeverNamesTaskDataAndUsesOnlyTheFiveColumns() {
        assertThat(problems(JooqScheduledTaskQueueReader.QUERY)).isEmpty();
    }

    @Test
    void theRealStatementReadsOnlyTheQueueTableOnceAndWritesNothing() {
        String sql = JooqScheduledTaskQueueReader.QUERY.toLowerCase(Locale.ROOT);

        assertThat(sql).containsOnlyOnce("scheduled_tasks");
        assertThat(sql.trim()).startsWith("select");
        assertThat(sql).doesNotContain("insert ").doesNotContain("update ").doesNotContain("delete ")
                .doesNotContain("for update");
    }

    @Test
    void theReaderClassHoldsOneStatementAndNoOther() {
        List<String> statements = Arrays.stream(JooqScheduledTaskQueueReader.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == String.class)
                .map(Field::getName).toList();

        assertThat(statements).as("string constants of the reader").containsExactly("QUERY");
    }

    @Test
    void aStatementThatSelectsTaskDataFailsTheInspection() {
        assertThat(problems(TaskDataSelectingReader.QUERY)).singleElement().asString().contains("task_data");
    }

    @Test
    void aStatementThatSelectsEveryColumnFailsTheInspection() {
        assertThat(problems(SelectStarReader.QUERY)).anyMatch(line -> line.contains("*"));
    }

    @Test
    void aColumnNamedOnlyInsideAStringLiteralIsNotCounted() {
        assertThat(problems("select task_name, execution_time, picked, last_heartbeat, consecutive_failures "
                + "from scheduled_tasks where task_name = 'task_data'")).isEmpty();
    }
}
