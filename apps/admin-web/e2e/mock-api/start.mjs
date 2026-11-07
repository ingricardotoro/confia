// Starts the simulated API for manual work and for Playwright (design.md D15):
//   pnpm --filter @confia/admin-web mock:api
// It is plain Node. Vite's runnerImport turns the TypeScript simulator, and the generated contract it
// imports, into something Node can run, without a new dependency. It binds to localhost only.
import { fileURLToPath } from "node:url";
import { runnerImport } from "vite";

const DEFAULT_PORT = 8099;
const raw = process.env.CONFIA_MOCK_API_PORT;
const port = raw === undefined || raw === "" ? DEFAULT_PORT : Number(raw);
if (!Number.isInteger(port) || port < 0 || port > 65535) {
  console.error("CONFIA_MOCK_API_PORT must be a port number.");
  process.exit(1);
}

const { module } = await runnerImport(fileURLToPath(new URL("./server.ts", import.meta.url)), {
  configFile: false,
  logLevel: "error",
});
const server = module.createMockServer();
const actual = await server.listen(port);
console.log(`Simulated API (not the real stack) listening on http://localhost:${actual}`);

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    void server.close().then(() => process.exit(0));
  });
}
