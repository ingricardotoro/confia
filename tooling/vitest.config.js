import { defineConfig } from "vitest/config";
import { confiaVitest } from "@confia/config/vitest";

// `turbo run lint typecheck test build` runs these tests next to the ui coverage run and the Storybook
// build. Alone the slowest takes under a second, but on a loaded machine even a one-file test went past
// the default 5 s: it timed out in several full runs during task 3.3. The limit is a ceiling for a busy
// runner, not a budget.
export default defineConfig({ test: { ...confiaVitest.test, testTimeout: 30_000 } });
