package com.confia.shared.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * CLAUDE.md regla 11 (web-edge-foundations design.md, decision 22): jOOQ logs every statement with
 * its bind values inlined at {@code DEBUG}, which would put a document number or a token in the
 * log. The production {@code DSLContext} switches that logging off in its settings, which holds
 * whatever the level of any logger. The query runs on jOOQ's mock connection, so no database is
 * needed; the control shows the same query on a default configuration does leak, so the check
 * can fail.
 */
class SqlLoggingTest {

    private static final String BIND_VALUE = "SECRETO-E";

    private static DataSource mockDatabase() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(new MockConnection(
                context -> new MockResult[] {new MockResult(0)}));
        return dataSource;
    }

    /** Every event jOOQ logs while a query with {@code BIND_VALUE} as a parameter runs. */
    private static List<String> eventsOf(DSLContext dsl) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger jooq = context.getLogger("org.jooq");
        Level before = jooq.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        jooq.addAppender(appender);
        jooq.setLevel(Level.TRACE);
        try {
            dsl.fetch("select ?", BIND_VALUE);
        } finally {
            jooq.detachAppender(appender);
            jooq.setLevel(before);
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void theProductionDslContextLogsNoStatementWithItsBindValues() throws SQLException {
        DSLContext production = new SharedPlatformConfiguration().dslContext(mockDatabase());

        assertThat(eventsOf(production)).noneMatch(message -> message.contains(BIND_VALUE));
        assertThat(production.settings().isExecuteLogging()).isFalse();
    }

    @Test
    void aDefaultDslContextDoesLogTheBindValueAtDebug() throws SQLException {
        DSLContext defaults = DSL.using(new DefaultConfiguration()
                .set(new org.jooq.impl.DataSourceConnectionProvider(mockDatabase()))
                .set(SQLDialect.POSTGRES));

        assertThat(eventsOf(defaults)).as("non-vacuous: jOOQ does log the statement by default")
                .anyMatch(message -> message.contains(BIND_VALUE));
    }
}
