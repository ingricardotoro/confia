package com.confia.shared.jobs;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Objects;

/**
 * When a {@link RecurringJob} runs (background-jobs-with-db-scheduler design.md decision 7). The
 * zone is not a parameter: every calendar is read in {@code America/Tegucigalpa}, which a module
 * cannot change, so no cadence depends on the default zone of the JVM or of the server.
 */
public sealed interface JobCalendar {

    /** Every day at {@code time}. */
    record DailyAt(LocalTime time) implements JobCalendar {

        public DailyAt {
            Objects.requireNonNull(time, "time");
        }
    }

    /** Every week on {@code day} at {@code time}. */
    record WeeklyAt(DayOfWeek day, LocalTime time) implements JobCalendar {

        public WeeklyAt {
            Objects.requireNonNull(day, "day");
            Objects.requireNonNull(time, "time");
        }
    }

    /** A fixed delay after each occurrence ends. */
    record Every(Duration interval) implements JobCalendar {

        public Every {
            Objects.requireNonNull(interval, "interval");
            if (interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException("interval must be positive");
            }
        }
    }
}
