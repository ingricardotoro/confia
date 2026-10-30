import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { listCssFiles, readFile, sourceDirectory, tokensDirectory } from "./__fixtures__/css";

// design.md decision 3: a primitive is named only in primitives.css (where it is defined) and
// semantic.css (where it is referenced). Any other stylesheet naming one skips the semantic level.
const ALLOWED = new Set([join(tokensDirectory, "primitives.css"), join(tokensDirectory, "semantic.css")]);

function paletteLeaks(paths: string[]): string[] {
  return paths.filter((path) => !ALLOWED.has(path) && readFile(path).includes("--palette-"));
}

describe("the stylesheets outside primitives.css and semantic.css", () => {
  const scanned = listCssFiles(sourceDirectory).filter((path) => !path.includes("__fixtures__"));

  it("are a real set that includes components.css and base.css", () => {
    const names = scanned.map((path) => path.slice(tokensDirectory.length + 1));

    expect(names).toEqual(expect.arrayContaining(["components.css", "base.css", "themes.css", "index.css"]));
  });

  it("never name a palette primitive [DS04]", () => {
    expect(paletteLeaks(scanned)).toEqual([]);
  });

  it("report a stylesheet that does", () => {
    const fixture = join(tokensDirectory, "__fixtures__", "palette-leak.css");

    expect(paletteLeaks([fixture])).toEqual([fixture]);
  });
});
