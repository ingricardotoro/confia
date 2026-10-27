package com.confia.shared.jobs;

import java.time.Duration;

/**
 * A bounded wait for a condition of the database or of the worker, polled every 200 ms. It is a
 * condition with a limit, never a fixed sleep that the test then trusts: the assertion is retried
 * until it holds or the limit passes, and then its own failure is the one reported.
 */
final class Eventually {

    /** An assertion that may throw a checked exception. */
    @FunctionalInterface
    interface Check {
        void run() throws Exception;
    }

    private Eventually() {
    }

    static void within(Duration limit, Check check) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (true) {
            try {
                check.run();
                return;
            } catch (Exception | AssertionError e) {
                if (System.nanoTime() >= deadline) {
                    if (e instanceof AssertionError assertion) {
                        throw assertion;
                    }
                    throw new AssertionError("the condition did not hold within " + limit, e);
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", interrupted);
            }
        }
    }

    /** Holds for the whole duration: the check is repeated until the limit and must pass every time. */
    static void throughout(Duration limit, Check check) {
        long deadline = System.nanoTime() + limit.toNanos();
        do {
            try {
                check.run();
            } catch (Exception e) {
                throw new AssertionError("the check failed", e);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting", interrupted);
            }
        } while (System.nanoTime() < deadline);
    }
}
