import { matchesGlob } from "node:path";

const normalize = (path: string) => path.replaceAll("\\", "/");

/**
 * The candidates a Vitest project collects: those matching an include and no exclude. Patterns are
 * matched against the candidate list, never expanded on disk, so an exclude such as
 * "**\/node_modules/**" costs nothing and cannot race a build writing into dist/.
 */
export function collectedFiles(
  candidates: readonly string[],
  include: readonly string[] | undefined,
  exclude: readonly string[] | undefined,
): Set<string> {
  const matches = (path: string, patterns: readonly string[] | undefined) =>
    (patterns ?? []).some((pattern) => matchesGlob(path, pattern));
  return new Set(
    candidates
      .map(normalize)
      .filter((path) => matches(path, include) && !matches(path, exclude)),
  );
}
