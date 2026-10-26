package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Scenario BJ49 (background-jobs-with-db-scheduler design.md decision 1): no versioned configuration
 * file turns the executor off, and a worker started with the test property that does says so with a
 * {@code WARN} and starts no executor thread. The property exists for the tests that need a worker
 * without a polling executor; it is never an operations switch.
 */
public class JobsConfigurationGuardTest {

    static final String CAPTURE_PROPERTY = "confia.test.capture-worker-log";

    private static final ListAppender<ILoggingEvent> CAPTURED = new ListAppender<>();

    private static final Pattern DISABLED = Pattern.compile(
            "(?m)^\\s*autostart\\s*:\\s*false\\s*$"
                    + "|confia\\.jobs\\.executor\\.autostart\\s*[:=]\\s*false");

    /**
     * Spring Boot re-initializes Logback when a process starts, which detaches any appender a test put
     * on a logger beforehand, so the capture is installed by an initializer, which runs after that. It
     * acts only in a context whose environment sets {@value #CAPTURE_PROPERTY}.
     */
    public static final class Capture
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            if (!"true".equals(context.getEnvironment().getProperty(CAPTURE_PROPERTY))) {
                return;
            }
            CAPTURED.start();
            rootLogger().addAppender(CAPTURED);
        }
    }

    /** Whether {@code content} switches the executor off. */
    static boolean disablesTheExecutor(String content) {
        return DISABLED.matcher(content).find();
    }

    @Test
    void noVersionedConfigurationFileDisablesTheExecutor() throws IOException {
        List<Path> files = versionedConfigurationFiles();

        assertThat(files).as("non-vacuous: the real application.yml is among the scanned files")
                .anyMatch(file -> file.getFileName().toString().equals("application.yml"));
        for (Path file : files) {
            assertThat(disablesTheExecutor(Files.readString(file)))
                    .as("%s must not disable the executor", file).isFalse();
        }
    }

    @Test
    void aFixtureThatDisablesTheExecutorIsDetectedInBothForms() {
        assertThat(disablesTheExecutor("confia:\n  jobs:\n    executor:\n      autostart: false\n"))
                .isTrue();
        assertThat(disablesTheExecutor("confia.jobs.executor.autostart=false\n")).isTrue();
        assertThat(disablesTheExecutor("confia:\n  jobs:\n    executor:\n      autostart: true\n"))
                .isFalse();
    }

    @Test
    void aWorkerStartedWithTheTestPropertyWarnsAndStartsNoExecutorThread() {
        CAPTURED.list.clear();
        ConfigurableApplicationContext context = null;
        try {
            LaunchOutcome outcome = ConfiaApplication.launch(
                    TestProcessArguments.forProcess("worker", "--" + CAPTURE_PROPERTY + "=true"),
                    "worker");
            context = outcome.context();
            assertThat(context).isNotNull();
            assertThat(CAPTURED.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("jobs_executor_not_started"));
            assertThat(Thread.getAllStackTraces().keySet().stream().map(Thread::getName)
                    .filter(name -> name.startsWith("db-scheduler")).toList()).isEmpty();
        } finally {
            rootLogger().detachAppender(CAPTURED);
            if (context != null) {
                context.close();
            }
        }
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }

    /** Every YAML and properties file under the module sources, outside build output. */
    private static List<Path> versionedConfigurationFiles() {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("src/main/resources"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("could not find src/main/resources");
        }
        try (Stream<Path> walk = Files.walk(root.resolve("src"))) {
            return walk.filter(Files::isRegularFile).filter(file -> {
                String name = file.getFileName().toString();
                return name.endsWith(".yml") || name.endsWith(".yaml")
                        || name.endsWith(".properties");
            }).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
