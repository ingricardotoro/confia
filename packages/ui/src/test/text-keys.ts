import { readdirSync, readFileSync } from "node:fs";
import { join, relative } from "node:path";
import ts from "typescript";

const srcRoot = join(import.meta.dirname, "..");
const packageRoot = join(srcRoot, "..");

export interface UsedTextKey {
  readonly key: string;
  /** Path relative to the package root, with forward slashes. */
  readonly file: string;
}

export interface KeyDiagnostic {
  readonly file: string;
  readonly message: string;
}

function toPosix(path: string): string {
  return path.replaceAll("\\", "/");
}

/** Dotted paths of every leaf of a nested catalog, the form `t()` receives. */
export function flattenCatalogKeys(catalog: object, prefix = ""): string[] {
  return Object.entries(catalog).flatMap(([name, value]: [string, unknown]) => {
    const path = prefix === "" ? name : `${prefix}.${name}`;
    return typeof value === "object" && value !== null ? flattenCatalogKeys(value, path) : [path];
  });
}

function listComponentFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) return entry.name === "__fixtures__" ? [] : listComponentFiles(path);
    return entry.name.endsWith(".tsx") ? [path] : [];
  });
}

function literalKeysIn(source: ts.SourceFile): string[] {
  const keys: string[] = [];
  const visit = (node: ts.Node): void => {
    if (ts.isCallExpression(node)) {
      const callee = node.expression;
      const isT =
        (ts.isIdentifier(callee) && callee.text === "t") ||
        (ts.isPropertyAccessExpression(callee) && callee.name.text === "t");
      const first = node.arguments[0];
      if (isT && first !== undefined && ts.isStringLiteralLike(first)) keys.push(first.text);
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  return keys;
}

/**
 * Walks every `src/**` `*.tsx` file (fixtures excluded) with the TypeScript compiler API and returns
 * each `t("...")` call whose first argument is a string literal (DS59).
 */
export function collectTextKeys(): UsedTextKey[] {
  return listComponentFiles(srcRoot).flatMap((path) => {
    const source = ts.createSourceFile(
      path,
      readFileSync(path, "utf8"),
      ts.ScriptTarget.Latest,
      true,
      ts.ScriptKind.TSX,
    );
    const file = toPosix(relative(packageRoot, path));
    return literalKeysIn(source).map((key) => ({ key, file }));
  });
}

// Reusing the previous program lets later checks skip re-parsing React, i18next and the DOM libs.
let previousProgram: ts.Program | undefined;

function compilerOptions(): ts.CompilerOptions {
  const config = ts.readConfigFile(join(packageRoot, "tsconfig.json"), (path) => ts.sys.readFile(path));
  return ts.parseJsonConfigFileContent(config.config, ts.sys, packageRoot).options;
}

/**
 * Type-checks `source` as if it were the file `virtualFile` inside the package, with the package's
 * own compiler options, and returns the diagnostics for that file. The text never touches the disk,
 * so a deliberate violation cannot break the package's own `tsc` run (BX06, BX07).
 */
export function checkVirtualFile(virtualFile: string, source: string): KeyDiagnostic[] {
  const options = compilerOptions();
  const virtualPath = toPosix(join(packageRoot, virtualFile));
  const host = ts.createCompilerHost(options);
  const fileExists = host.fileExists.bind(host);
  const readFile = host.readFile.bind(host);
  const getSourceFile = host.getSourceFile.bind(host);
  host.fileExists = (name) => toPosix(name) === virtualPath || fileExists(name);
  host.readFile = (name) => (toPosix(name) === virtualPath ? source : readFile(name));
  host.getSourceFile = (name, languageVersionOrOptions, onError, shouldCreate) =>
    toPosix(name) === virtualPath
      ? ts.createSourceFile(name, source, languageVersionOrOptions, true, ts.ScriptKind.TSX)
      : getSourceFile(name, languageVersionOrOptions, onError, shouldCreate);

  const program = ts.createProgram({
    rootNames: [virtualPath],
    options,
    host,
    ...(previousProgram === undefined ? {} : { oldProgram: previousProgram }),
  });
  previousProgram = program;
  const target = program.getSourceFile(virtualPath);
  if (target === undefined) throw new Error(`The virtual file ${virtualFile} was not loaded`);
  return program.getSemanticDiagnostics(target).map((diagnostic) => ({
    file: toPosix(relative(packageRoot, diagnostic.file?.fileName ?? virtualPath)),
    message: ts.flattenDiagnosticMessageText(diagnostic.messageText, "\n"),
  }));
}
