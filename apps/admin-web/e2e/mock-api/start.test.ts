// @vitest-environment node
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// admin-web-foundations-and-session, task 11.1, the first test (design.md D15): `runnerImport` of Vite must
// not externalize a linked package, or the simulator could not load the generated contract, whose files
// are TypeScript. The script is started as a separate Node process, the way `pnpm mock:api` does it. The
// written way out if this ever fails is to start the simulator from Playwright's `globalSetup` (task 12).
const SCRIPT = join(import.meta.dirname, "start.mjs");
const LIMIT = 120_000;

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const probe = createServer();
    probe.on("error", reject);
    probe.listen(0, "127.0.0.1", () => {
      const address = probe.address();
      probe.close(() => {
        if (address === null || typeof address === "string") reject(new Error("no port"));
        else resolve(address.port);
      });
    });
  });
}

describe("start.mjs", { timeout: LIMIT }, () => {
  it("loads the TypeScript simulator and the linked contract package, and answers on localhost", async () => {
    const port = await freePort();
    const child = spawn(process.execPath, [SCRIPT], {
      env: { ...process.env, CONFIA_MOCK_API_PORT: String(port) },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    child.stdout.on("data", (chunk: Buffer) => (output += chunk.toString()));
    child.stderr.on("data", (chunk: Buffer) => (output += chunk.toString()));
    try {
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => { reject(new Error(`the simulator did not start: ${output}`)); }, 90_000);
        child.on("exit", (code) => {
          clearTimeout(timer);
          reject(new Error(`the simulator ended with ${String(code)}: ${output}`));
        });
        child.stdout.on("data", () => {
          if (output.includes(`http://localhost:${String(port)}`)) {
            clearTimeout(timer);
            resolve();
          }
        });
      });

      const health = await fetch(`http://localhost:${String(port)}/__control/health`);

      expect(health.status).toBe(200);
      expect(output).not.toContain("0.0.0.0");
    } finally {
      child.kill();
    }
  });
});
