import { describe, expect, it } from "vitest";
import { collectedFiles } from "./collection";

// test-collection.test.ts decides which files a Vitest project collects. Excludes are matched against
// a list of candidate files and never expanded on disk: expanding "**/node_modules/**" and
// "**/dist/**" walks the whole package, including the folders the parallel storybook build writes.
describe("the files a project collects", () => {
  const candidates = [
    "src/a/a.test.ts",
    "src/a/a.a11y.test.tsx",
    "src/a/__fixtures__/f.test.ts",
    "src/dist/generated.test.ts",
    "src/a/a.stories.tsx",
  ];

  it("keeps the files that match an include and no exclude", () => {
    expect(collectedFiles(candidates, ["src/**/*.test.{ts,tsx}"], [])).toEqual(
      new Set(["src/a/a.test.ts", "src/a/a.a11y.test.tsx", "src/a/__fixtures__/f.test.ts", "src/dist/generated.test.ts"]),
    );
  });

  it("drops the files that match any exclude, at any depth", () => {
    const files = collectedFiles(
      candidates,
      ["src/**/*.test.{ts,tsx}"],
      ["src/**/*.a11y.test.tsx", "src/**/__fixtures__/**", "**/dist/**"],
    );

    expect(files).toEqual(new Set(["src/a/a.test.ts"]));
  });

  it("collects nothing when there is no include", () => {
    expect(collectedFiles(candidates, [], ["**/dist/**"]).size).toBe(0);
    expect(collectedFiles(candidates, undefined, undefined).size).toBe(0);
  });

  it("treats backslashes of a Windows path as separators", () => {
    expect(collectedFiles([String.raw`src\a\a.test.ts`], ["src/**/*.test.ts"], undefined)).toEqual(new Set(["src/a/a.test.ts"]));
  });
});
