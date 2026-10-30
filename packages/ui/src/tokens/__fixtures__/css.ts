import { readFileSync, readdirSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { compile } from "tailwindcss";

// Shared test utilities for the token tests: read the real stylesheets, parse their rules, resolve
// var() chains and compile the entry point with Tailwind. Not part of the package: tests only.

export const tokensDirectory = join(import.meta.dirname, "..");
export const sourceDirectory = join(tokensDirectory, "..");

export const readToken = (name: string): string => readFileSync(join(tokensDirectory, name), "utf8");
export const readFixture = (name: string): string => readFileSync(join(import.meta.dirname, name), "utf8");
export const readFile = (path: string): string => readFileSync(path, "utf8");

/** The four files that declare custom properties; base.css and index.css declare none. */
export const TOKEN_FILES = ["primitives.css", "semantic.css", "themes.css", "components.css"] as const;

export const realTokenSources = (): string[] => TOKEN_FILES.map(readToken);

const squash = (text: string): string => text.replace(/\s+/g, " ").trim();

export interface CssRule {
  /** Enclosing preludes from the outermost at-rule down to the rule's own selector. */
  context: string[];
  declarations: Map<string, string>;
}

/** A small CSS reader: enough for flat rules and one level of at-rule nesting, with no strings holding braces. */
export function parseRules(source: string): CssRule[] {
  const css = source.replace(/\/\*[\s\S]*?\*\//g, "");
  const rules: CssRule[] = [];
  const stack: { prelude: string; declarations: Map<string, string> }[] = [];
  let buffer = "";

  const flushDeclaration = () => {
    const frame = stack.at(-1);
    const text = buffer.trim();
    buffer = "";
    const colon = text.indexOf(":");
    if (frame && colon > 0) {
      frame.declarations.set(text.slice(0, colon).trim(), squash(text.slice(colon + 1)));
    }
  };

  for (const char of css) {
    if (char === "{") {
      stack.push({ prelude: squash(buffer), declarations: new Map() });
      buffer = "";
    } else if (char === ";") {
      flushDeclaration();
    } else if (char === "}") {
      flushDeclaration();
      const frame = stack.pop();
      if (frame) {
        rules.push({
          context: [...stack.map((outer) => outer.prelude), frame.prelude],
          declarations: frame.declarations,
        });
      }
    } else {
      buffer += char;
    }
  }
  return rules;
}

export const DARK_MEDIA_CONTEXT = ["@media (prefers-color-scheme: dark)", ":root:not([data-theme='light'])"];
export const DARK_ATTRIBUTE_CONTEXT = [":root[data-theme='dark']"];

const sameContext = (rule: CssRule, context: string[]): boolean =>
  rule.context.length === context.length && rule.context.every((part, index) => part === context[index]);

const BASE_CONTEXTS = [["@theme static"], ["@theme"], [":root"]];

/** Light (base) values: everything declared in @theme or on :root, across the given sources. */
export function baseDeclarations(sources: string[]): Map<string, string> {
  const merged = new Map<string, string>();
  for (const rule of sources.flatMap(parseRules)) {
    if (BASE_CONTEXTS.some((context) => sameContext(rule, context))) {
      for (const [name, value] of rule.declarations) merged.set(name, value);
    }
  }
  return merged;
}

export interface DarkBlocks {
  media: Map<string, string> | undefined;
  attribute: Map<string, string> | undefined;
}

export function darkBlocks(sources: string[]): DarkBlocks {
  const rules = sources.flatMap(parseRules);
  return {
    media: rules.find((rule) => sameContext(rule, DARK_MEDIA_CONTEXT))?.declarations,
    attribute: rules.find((rule) => sameContext(rule, DARK_ATTRIBUTE_CONTEXT))?.declarations,
  };
}

export type Theme = "light" | "dark" | "dark-media";

/** Declarations in force for a theme: the base values, with the matching dark block on top for either dark. */
export function themeDeclarations(sources: string[], theme: Theme): Map<string, string> {
  const merged = baseDeclarations(sources);
  if (theme !== "light") {
    const blocks = darkBlocks(sources);
    for (const [name, value] of (theme === "dark" ? blocks.attribute : blocks.media) ?? []) merged.set(name, value);
  }
  return merged;
}

/** Follows var(--x) references until a literal; throws naming the token on a cycle or a gap. */
export function resolveToken(name: string, declarations: Map<string, string>, seen: string[] = []): string {
  if (seen.includes(name)) throw new Error(`Circular reference: ${[...seen, name].join(" -> ")}`);
  const value = declarations.get(name);
  if (value === undefined) {
    const referrer = seen.at(-1);
    throw new Error(`${name} has no value${referrer === undefined ? "" : ` (referenced by ${referrer})`}`);
  }
  return value.replace(/var\((--[\w-]+)\)/g, (_match, inner: string) =>
    resolveToken(inner, declarations, [...seen, name]),
  );
}

export interface ParityReport {
  problems: string[];
  /** Number of dark variables evaluated, so an empty tree cannot pass (ADR-0018). */
  evaluated: number;
}

/** docs/ui-ux/01 section 6 and design decision 4: identical dark blocks, and a base value for each variable. */
export function parityReport(sources: string[]): ParityReport {
  const { media, attribute } = darkBlocks(sources);
  const problems: string[] = [];
  if (!media) problems.push("the prefers-color-scheme dark block is missing");
  if (!attribute) problems.push("the data-theme='dark' block is missing");
  const base = baseDeclarations(sources);
  const names = new Set([...(media?.keys() ?? []), ...(attribute?.keys() ?? [])]);

  for (const name of [...names].sort()) {
    const inMedia = media?.get(name);
    const inAttribute = attribute?.get(name);
    if (inMedia === undefined) problems.push(`${name} is missing from the prefers-color-scheme block`);
    else if (inAttribute === undefined) problems.push(`${name} is missing from the data-theme='dark' block`);
    else if (inMedia !== inAttribute) {
      problems.push(`${name} differs between the dark blocks: ${inMedia} vs ${inAttribute}`);
    }
    if (!base.has(name)) problems.push(`${name} has no base value outside the dark blocks`);
  }
  return { problems, evaluated: names.size };
}

export function listCssFiles(directory: string): string[] {
  return readdirSync(directory, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(".css"))
    .map((entry) => join(entry.parentPath, entry.name))
    .filter((path) => !path.includes("node_modules"));
}

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
