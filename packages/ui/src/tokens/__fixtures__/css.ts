import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { compile } from "tailwindcss";

// Shared test utilities for the token tests: read the real stylesheets and compile the entry point
// with Tailwind. Not part of the package: tests only.

export const tokensDirectory = join(import.meta.dirname, "..");

export const readToken = (name: string): string => readFileSync(join(tokensDirectory, name), "utf8");
export const readFile = (path: string): string => readFileSync(path, "utf8");

const require = createRequire(import.meta.url);
const tailwindDirectory = dirname(require.resolve("tailwindcss/package.json"));

function loadStylesheet(id: string, base: string) {
  let path: string;
  if (id === "tailwindcss") path = join(tailwindDirectory, "index.css");
  else if (id.startsWith("tailwindcss/")) path = join(tailwindDirectory, id.slice("tailwindcss/".length));
  else path = resolve(base, id);
  return Promise.resolve({ path, base: dirname(path), content: readFile(path) });
}

/** Compiles the real entry point (or another stylesheet in src/tokens) and returns the utilities for the candidates. */
export async function buildCss(candidates: string[], entry = "index.css"): Promise<string> {
  const compiler = await compile(readToken(entry), { base: tokensDirectory, loadStylesheet });
  return compiler.build(candidates);
}
