import { readFileSync } from "node:fs";
import { join } from "node:path";
import { core } from "zod";
import { describe, expect, it } from "vitest";
import "./zod-config.ts";

// AW12. Zod probes `Function("")` the first time a schema is used, and the strict CSP refuses it: the
// browser reports a securitypolicyviolation even though Zod catches the failure. `jitless` skips the probe.
describe("the Zod configuration", () => {
  it("runs Zod without JIT, so it never evaluates code under the content security policy", () => {
    expect(core.globalConfig.jitless).toBe(true);
  });

  it("is the first import of the entry point, because imports run in order and a schema built earlier would probe", () => {
    const entry = readFileSync(join(import.meta.dirname, "..", "main.tsx"), "utf8");
    const firstImport = /^import\s[^;]*?"([^"]+)";/m.exec(entry)?.[1];

    expect(firstImport).toBe("./app/zod-config.ts");
  });
});
