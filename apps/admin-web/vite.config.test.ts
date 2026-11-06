import { readFileSync } from "node:fs";
import { createServer as createHttpServer, request, type IncomingHttpHeaders, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer, preview, type PreviewServer, type ViteDevServer } from "vite";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { resolveApiTarget } from "./vite.config.ts";

// admin-web-foundations-and-session, task 2.2 (design.md D4). The proxy is the only way the page reaches
// the API: it must forward the path and method untouched, keep Origin (the backend CSRF filter compares
// it with its allowed list) and keep Host, because production nginx keeps it too (AW07, AW08). No
// Chromium: a Node client plays the browser and a Node server plays the backend.
const PACKAGE_ROOT = import.meta.dirname;
const CONFIG_FILE = join(PACKAGE_ROOT, "vite.config.ts");
const PAGE_ORIGIN = "http://localhost:5173";

interface Received {
  readonly method: string | undefined;
  readonly url: string | undefined;
  readonly headers: IncomingHttpHeaders;
}

function listen(server: Server): Promise<number> {
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      resolve((server.address() as AddressInfo).port);
    });
  });
}

function send(port: number, method: string, path: string): Promise<{ status: number; headers: IncomingHttpHeaders }> {
  return new Promise((resolve, reject) => {
    const req = request(
      { host: "localhost", port, method, path, headers: { Origin: PAGE_ORIGIN } },
      (res) => {
        res.resume();
        res.on("end", () => {
          resolve({ status: res.statusCode ?? 0, headers: res.headers });
        });
      },
    );
    req.on("error", reject);
    req.end();
  });
}

// The time limit is explicit because this suite builds the whole app graph cold (or starts a real
// server), which can outlast Vitest's 5 s default on a loaded machine; no assertion waits on it.
describe("the same-origin proxy", { timeout: 20_000 }, () => {
  let backend: Server;
  let received: Received[];
  let backendPort: number;
  let dev: ViteDevServer | undefined;
  let served: PreviewServer | undefined;
  let outDir: string;

  beforeEach(async () => {
    received = [];
    backend = createHttpServer((req, res) => {
      received.push({ method: req.method, url: req.url, headers: req.headers });
      res.writeHead(204).end();
    });
    backendPort = await listen(backend);
    process.env["CONFIA_API_PROXY_TARGET"] = `http://127.0.0.1:${String(backendPort)}`;
    outDir = mkdtempSync(join(tmpdir(), "confia-preview-"));
    writeFileSync(join(outDir, "index.html"), "<!doctype html><title>t</title>");
  });

  afterEach(async () => {
    delete process.env["CONFIA_API_PROXY_TARGET"];
    await dev?.close();
    await served?.close();
    dev = undefined;
    served = undefined;
    await new Promise((resolve) => backend.close(resolve));
    rmSync(outDir, { recursive: true, force: true });
  });

  it("AW07: dev forwards the path and method with Origin and Host intact", async () => {
    dev = await createServer({ configFile: CONFIG_FILE, root: PACKAGE_ROOT, logLevel: "silent", server: { port: 0, strictPort: false } });
    await dev.listen();
    const port = (dev.httpServer?.address() as AddressInfo).port;

    const response = await send(port, "POST", "/api/v1/auth/sessions");

    expect(response.status).toBe(204);
    expect(received).toHaveLength(1);
    expect(received[0]?.method).toBe("POST");
    expect(received[0]?.url).toBe("/api/v1/auth/sessions");
    expect(received[0]?.headers["origin"]).toBe(PAGE_ORIGIN);
    expect(received[0]?.headers["host"]).toBe(`localhost:${String(port)}`);
  });

  it("AW08: preview forwards like dev, and answers with the strict content security policy", async () => {
    served = await preview({
      configFile: CONFIG_FILE,
      root: PACKAGE_ROOT,
      logLevel: "silent",
      build: { outDir },
      preview: { port: 0, strictPort: false },
    });
    const port = (served.httpServer.address() as AddressInfo).port;

    const response = await send(port, "POST", "/api/v1/auth/sessions/refresh");

    expect(response.status).toBe(204);
    expect(received[0]?.method).toBe("POST");
    expect(received[0]?.url).toBe("/api/v1/auth/sessions/refresh");
    expect(received[0]?.headers["origin"]).toBe(PAGE_ORIGIN);
    expect(received[0]?.headers["host"]).toBe(`localhost:${String(port)}`);

    const page = await send(port, "GET", "/");
    const policy = String(page.headers["content-security-policy"]);
    expect(policy).toContain("default-src 'none'");
    expect(policy).toContain("script-src 'self'");
    expect(policy).toContain("connect-src 'self'");
    expect(policy).not.toContain("unsafe-inline");
    expect(policy).not.toContain("unsafe-eval");
  });

  it("AW11: adds no CORS header to a proxied answer", async () => {
    dev = await createServer({ configFile: CONFIG_FILE, root: PACKAGE_ROOT, logLevel: "silent", server: { port: 0, strictPort: false } });
    await dev.listen();
    const port = (dev.httpServer?.address() as AddressInfo).port;

    const response = await send(port, "GET", "/api/v1/auth/sessions");

    expect(Object.keys(response.headers).filter((name) => name.startsWith("access-control-"))).toEqual([]);
  });
});

describe("resolveApiTarget", () => {
  it("defaults to the local backend", () => {
    expect(resolveApiTarget(undefined)).toBe("http://localhost:8080");
    expect(resolveApiTarget("")).toBe("http://localhost:8080");
  });

  it.each(["http://localhost:9000", "http://127.0.0.1:8080", "https://localhost:8443"])("accepts the local target %s", (target) => {
    expect(resolveApiTarget(target)).toBe(target);
  });

  it.each([
    "http://example.com:8080",
    "http://localhost.evil.test",
    "http://127.0.0.1.evil.test",
    "http://user@evil.test",
    "http://[::1]:8080",
    "ftp://localhost",
    "not a url",
  ])("aborts on the non local target %s, naming the variable and not echoing the value", (target) => {
    expect(() => resolveApiTarget(target)).toThrow(/CONFIA_API_PROXY_TARGET/);
    expect(() => resolveApiTarget(target)).not.toThrow(target);
  });
});

describe("the configuration of the build and of the servers", () => {
  const source = readFileSync(CONFIG_FILE, "utf8");

  it("AW11: carries no CORS header and no switch that removes Secure", () => {
    const scripts = JSON.parse(readFileSync(join(PACKAGE_ROOT, "package.json"), "utf8")) as { scripts: Record<string, string> };
    const surface = `${source}\n${JSON.stringify(scripts.scripts)}`;

    expect(surface).not.toMatch(/access-control-allow/i);
    expect(surface).not.toMatch(/\bcors\s*:\s*(true|\{)/i);
    expect(surface).not.toMatch(/disable-?secure|no-?secure|insecure-?cookie|\bsecure\s*[:=]\s*false/i);
  });

  it("D4: keeps public assets out of the build and never inlines a font as data:", () => {
    expect(source).toMatch(/publicDir:\s*false/);
    expect(source).toMatch(/assetsInlineLimit:\s*0/);
    expect(source).toMatch(/changeOrigin:\s*false/);
  });
});
