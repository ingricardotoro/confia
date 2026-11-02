package com.confia.shared.infrastructure;

import com.confia.shared.jobs.infrastructure.ParkedExecutions;
import com.confia.shared.observability.queue.QueueSnapshot;
import com.confia.shared.observability.queue.QueueStateReader;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/**
 * The adapter of {@link QueueStateReader} over {@code scheduled_tasks} (background-jobs-with-db-scheduler
 * design.md decision 20; scenarios BJ32, BJ33, BJ67 and BI101). It is the third entry of the approved list of
 * plain SQL of ADR-0015 rule 9, because jOOQ generates no code for the table the library owns (ADR-0017 rule 6),
 * and the only class that reads that table for the metrics.
 *
 * <p>It runs {@link #QUERY}, one read-only statement whose every value is a bound parameter. The statement names
 * {@code task_name}, {@code execution_time}, {@code picked}, {@code last_heartbeat} and {@code
 * consecutive_failures} and no other column, never {@code task_data}, which can carry identifiers of the
 * business; {@code QueueSqlNeverReadsTaskDataTest} inspects its text. It classifies each row in this order, so a
 * row is counted once:
 *
 * <ol>
 *   <li>failed: parked (its instant is the parking instant or later), or a recurring task with {@code
 *       consecutive_failures} above zero. A one-time task between two attempts keeps its failures and is not
 *       failed until it is parked;
 *   <li>running: taken by a worker;
 *   <li>due: its instant is not after {@code now};
 *   <li>scheduled: the rest.
 * </ol>
 *
 * <p>The age is how long the oldest of these has waited: a task that no worker has taken, whose instant is not
 * after {@code now} and is not the parking one, counted from {@code execution_time}; and a task a worker took but
 * whose last heartbeat is older than the limit of missed heartbeats, counted from {@code last_heartbeat}, because
 * that worker is presumed dead and the task is stalled. A parked task and a task that beats do not count. A
 * failing recurring task that is overdue counts, even though it is classified as failed.
 */
public final class JooqScheduledTaskQueueReader implements QueueStateReader {

    /** The statement. It is public only so that the static inspection of the tests can read the real text. */
    public static final String QUERY = """
            select task_name,
                   case when execution_time >= cast(? as timestamptz)
                             or (task_name = any(?) and consecutive_failures > 0) then 'failed'
                        when picked then 'running'
                        when execution_time <= cast(? as timestamptz) then 'due'
                        else 'scheduled' end as state,
                   count(*) as tasks,
                   min(case when not picked and execution_time <= cast(? as timestamptz)
                                 and execution_time < cast(? as timestamptz) then execution_time
                            when picked and last_heartbeat < cast(? as timestamptz) then last_heartbeat end) as oldest
              from scheduled_tasks
             group by task_name, 2
            """;

    private final DSLContext dsl;
    private final Duration heartbeatLimit;
    private final Supplier<? extends Collection<String>> recurringTaskNames;

    /**
     * @param dsl the context of the worker
     * @param heartbeatLimit how long a taken task may go without a heartbeat before it counts as stalled: the
     *     heartbeat interval of the executor times its limit of missed heartbeats
     * @param recurringTaskNames the names of the recurring jobs, asked at each reading, so a job registered later
     *     is seen
     */
    public JooqScheduledTaskQueueReader(DSLContext dsl, Duration heartbeatLimit,
            Supplier<? extends Collection<String>> recurringTaskNames) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.heartbeatLimit = Objects.requireNonNull(heartbeatLimit, "heartbeatLimit");
        this.recurringTaskNames = Objects.requireNonNull(recurringTaskNames, "recurringTaskNames");
    }

    @Override
    public QueueSnapshot read(Instant now) {
        OffsetDateTime reading = now.atOffset(ZoneOffset.UTC);
        OffsetDateTime parked = ParkedExecutions.PARKED_AT.atOffset(ZoneOffset.UTC);
        OffsetDateTime heartbeatCutoff = now.minus(heartbeatLimit).atOffset(ZoneOffset.UTC);
        String[] recurring = recurringTaskNames.get().toArray(String[]::new);

        long scheduled = 0;
        long due = 0;
        long running = 0;
        Map<String, Long> failedByTask = new TreeMap<>();
        Instant oldest = null;
        // The values follow the question marks of QUERY, in order.
        Result<Record> rows = dsl.resultQuery(QUERY, DSL.val(parked),
                DSL.val(recurring, SQLDataType.VARCHAR.getArrayDataType()), DSL.val(reading), DSL.val(reading),
                DSL.val(parked), DSL.val(heartbeatCutoff)).fetch();
        for (Record row : rows) {
            long tasks = row.get("tasks", Long.class);
            switch (row.get("state", String.class)) {
                case "failed" -> failedByTask.merge(row.get("task_name", String.class), tasks, Long::sum);
                case "running" -> running += tasks;
                case "due" -> due += tasks;
                default -> scheduled += tasks;
            }
            OffsetDateTime rowOldest = row.get("oldest", OffsetDateTime.class);
            if (rowOldest != null && (oldest == null || rowOldest.toInstant().isBefore(oldest))) {
                oldest = rowOldest.toInstant();
            }
        }
        return new QueueSnapshot(scheduled, due, running, failedByTask,
                oldest == null ? Duration.ZERO : Duration.between(oldest, now));
    }
}
