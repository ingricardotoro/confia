package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Probe P-10 and scenario BJ50 (background-jobs-with-db-scheduler design.md decision 1): the worker
 * has no web server, so nothing but a non-daemon thread keeps its JVM from ending when {@code main}
 * returns. {@code spring.main.keep-alive: true} in {@code application.yml} is that thread. The
 * test runs the real launcher in a child JVM, with the executor off so the keep-alive is the only
 * thing that could hold the process, and shows two things: it is still running a while after the
 * context was ready, and with the property switched off on the command line it ends by itself, so
 * the property is what keeps it alive.
 */
class WorkerStaysAliveTest {

    private static final String READY = "Started ConfiaApplication";
    private static final long START_TIMEOUT_SECONDS = 90;
    private static final long OBSERVE_SECONDS = 6;

    /** A child JVM and what it has written so far; a thread drains the pipe so it never blocks. */
    private static final class Child implements AutoCloseable {

        private final Process process;
        private final StringBuffer output = new StringBuffer();

        Child(List<String> extra) throws IOException {
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add("com.confia.bootstrap.ConfiaApplication");
            command.add("--spring.datasource.url=" + TestProcessArguments.UNREACHABLE_JDBC_URL);
            command.add("--confia.jobs.institutions=" + UUID.randomUUID());
            command.add("--confia.jobs.executor.autostart=false");
            command.addAll(extra);
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("APP_PROFILE", "worker");
            this.process = builder.start();
            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                } catch (IOException e) {
                    output.append("(output closed: ").append(e.getMessage()).append(")\n");
                }
            }, "worker-child-output");
            drain.setDaemon(true);
            drain.start();
        }

        boolean waitForLine(String line) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                if (output.indexOf(line) >= 0) {
                    return true;
                }
                if (!process.isAlive()) {
                    Thread.sleep(300);
                    return output.indexOf(line) >= 0;
                }
                Thread.sleep(200);
            }
            return false;
        }

        String tail() {
            String text = output.toString();
            return text.substring(Math.max(0, text.length() - 1500));
        }

        @Override
        public void close() {
            process.destroyForcibly();
        }
    }

    @Test
    void theWorkerJvmKeepsRunningWithoutAWebServer() throws Exception {
        try (Child child = new Child(List.of())) {
            assertThat(child.waitForLine(READY)).as("the worker reports it started; output: %s",
                    child.tail()).isTrue();

            boolean ended = child.process.waitFor(OBSERVE_SECONDS, TimeUnit.SECONDS);

            assertThat(ended).as("the worker JVM must still be running; output: %s", child.tail())
                    .isFalse();
        }
    }

    @Test
    void withTheKeepAliveSwitchedOffTheSameJvmEndsOnItsOwn() throws Exception {
        try (Child child = new Child(List.of("--spring.main.keep-alive=false"))) {
            boolean ended = child.process.waitFor(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(ended).as("without keep-alive the worker JVM ends; output: %s", child.tail())
                    .isTrue();
            assertThat(child.waitForLine(READY)).as("it did start first; output: %s", child.tail())
                    .isTrue();
        }
    }
}
