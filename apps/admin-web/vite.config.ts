import { randomBytes } from "node:crypto";
import { readFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vite";

// admin-web-foundations-and-session, design.md D4. The browser reaches the API only through this
// proxy, on the page's own origin: no cross-origin headers, no preflight, and Origin and Host arrive at the backend as
// the page sent them (the backend CSRF filter compares Origin with its allowed list, which holds
// http://localhost:5173 and nothing else).
const DEFAULT_API_TARGET = "http://localhost:8080";
const LOCAL_HOSTS: ReadonlySet<string> = new Set(["localhost", "127.0.0.1"]);

/**
 * The proxy target comes from CONFIA_API_PROXY_TARGET (no VITE_ prefix, so it never reaches the
 * bundle). Only the local machine is accepted: a mistyped variable must not send credentials to
 * another host. The message names the variable and never echoes the value.
 */
export function resolveApiTarget(value: string | undefined): string {
  if (value === undefined || value === "") return DEFAULT_API_TARGET;
  const refusal = new Error(
    "CONFIA_API_PROXY_TARGET must be an http or https URL whose host is localhost or 127.0.0.1.",
  );
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw refusal;
  }
  const allowed =
    (url.protocol === "http:" || url.protocol === "https:") &&
    LOCAL_HOSTS.has(url.hostname) &&
    url.username === "" &&
    url.password === "";
  if (!allowed) throw refusal;
  return value;
}

// The CSP of docs/03 without what depends on production: script-src 'self' instead of a nonce (the
// nonce arrives with change 11), no upgrade-insecure-requests and no report-uri, because there is no
// HTTPS and no report endpoint here. connect-src 'self' follows the note of 2026-10-23.
//
// style-src also accepts the nonce of the response: the scroll lock of a modal Dialog (Radix, react-remove-scroll) adds one
// <style> element at run time and the page hands it that nonce (src/main.tsx). Nothing else is allowed inline.
const NONCE_PLACEHOLDER = "__CSP_NONCE__";
const policy = (styleNonce?: string): string => [
  "default-src 'none'",
  "script-src 'self'",
  styleNonce === undefined ? "style-src 'self'" : `style-src 'self' 'nonce-${styleNonce}'`,
  "img-src 'self' data: blob:",
  "font-src 'self'",
  "connect-src 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
  "base-uri 'none'",
  "object-src 'none'",
  "worker-src 'self' blob:",
  "manifest-src 'self'",
].join("; ");
const CSP = policy();

/**
 * `vite preview` stands in for the server that will issue the policy in production: every HTML response gets a fresh
 * 128-bit nonce, written into the meta tag of the page and into the policy header, never reused. Assets keep the policy
 * without a nonce.
 */
export function previewNonce(): Plugin {
  return {
    name: "confia-preview-csp-nonce",
    configurePreviewServer(server) {
      const outDir = resolve(server.config.root, server.config.build.outDir);
      server.middlewares.use((request, response, next) => {
        const path = (request.url ?? "/").split("?")[0] ?? "/";
        const isPage = request.method === "GET" && !path.startsWith("/api") && !/\.[A-Za-z0-9]+$/.test(path);
        if (!isPage) {
          next();
          return;
        }
        readFile(join(outDir, "index.html"), "utf8").then(
          (html) => {
            const nonce = randomBytes(16).toString("base64");
            response.setHeader("Content-Type", "text/html; charset=utf-8");
            response.setHeader("Content-Security-Policy", policy(nonce));
            response.setHeader("Cache-Control", "no-store");
            response.end(html.replaceAll(NONCE_PLACEHOLDER, nonce));
          },
          () => {
            next();
          },
        );
      });
    },
  };
}

export default defineConfig(() => {
  const proxy = {
    "/api": { target: resolveApiTarget(process.env["CONFIA_API_PROXY_TARGET"]), changeOrigin: false },
  };
  return {
    plugins: [react(), tailwindcss(), previewNonce()],
    // Nothing is copied into dist as-is: a future `msw init public` must not ship a worker.
    publicDir: false as const,
    // A small font inlined as data: would break font-src 'self'.
    build: { assetsInlineLimit: 0 },
    // cors: false because Vite would otherwise answer a localhost Origin with a cross-origin allow header,
    // and AW11 says nothing here may add cross-origin headers (the page is same-origin anyway).
    // 5173 is the only Origin the local backend allows, so a busy port is an error, not a new port.
    server: { host: "localhost", port: 5173, strictPort: true, cors: false, proxy },
    preview: { host: "localhost", port: 5173, strictPort: true, cors: false, proxy, headers: { "Content-Security-Policy": CSP } },
  };
});
