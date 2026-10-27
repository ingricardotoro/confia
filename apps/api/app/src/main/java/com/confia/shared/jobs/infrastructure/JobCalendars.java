package com.confia.shared.jobs.infrastructure;

import com.confia.shared.jobs.JobCalendar;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Translates a {@link JobCalendar} into the library's schedule, always in {@link #ZONE}
 * (background-jobs-with-db-scheduler design.md decision 7, probe P-5; ADR-0016). The zone is a
 * constant, not a parameter: a module cannot choose another, and nothing here reads the default zone
 * of the JVM or of the server. Honduras has no daylight saving time, so the offset is constant.
 */
public final class JobCalendars {

    /** The zone of every cadence of the system. */
    public static final ZoneId ZONE = ZoneId.of("America/Tegucigalpa");

    private JobCalendars() {
    }

    public static Schedule scheduleOf(JobCalendar calendar) {
        return switch (calendar) {
            case JobCalendar.DailyAt daily -> Schedules.cron(
                    "0 " + daily.time().getMinute() + " " + daily.time().getHour() + " * * *", ZONE);
            case JobCalendar.WeeklyAt weekly -> Schedules.cron(
                    "0 " + weekly.time().getMinute() + " " + weekly.time().getHour() + " * * "
                            + dayOf(weekly.day()), ZONE);
            case JobCalendar.Every every -> Schedules.fixedDelay(every.interval());
        };
    }

    /** Three-letter English day name, as the cron parser expects. */
    private static String dayOf(DayOfWeek day) {
        return day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ENGLISH);
    }
}
