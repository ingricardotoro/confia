import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { MATRIX, MINIMUM } from "./a11y-matrix.ts";
import { THEMES, assertCoversMinimum, assertNoBlocking, summarizeReport, type GateResult, type Result } from "./a11y-report.ts";
import { SCENARIO_HEADER } from "./scenario-header.ts";
import { SCENARIO_HEADER as SERVER_HEADER } from "../mock-api/server.ts";

const violation = (id: string, impact: Result["impact"]): Result =>
  ({ id, impact, help: `${id} help`, nodes: [{ target: ["#x"] }] }) as unknown as Result;
const result = (blocking: Result[], theme: GateResult["theme"] = "light"): GateResult => ({
  route: "/inaccessible",
  state: "ready",
  theme,
  blocking,
  reported: [],
});

describe("the evaluations of the matrix [BX74, BX77]", () => {
  it("covers every minimum combination in both themes, with the minimum written apart from the matrix", () => {
    const evaluations = assertCoversMinimum(MATRIX, MINIMUM);

    expect(MINIMUM).toHaveLength(6);
    for (const wanted of MINIMUM) {
      const themes = evaluations.filter((e) => e.route === wanted.route && e.state === wanted.state).map((e) => e.theme);
      expect(themes).toEqual([...THEMES]);
    }
    // Every combination of the matrix is evaluated, the minimum and the screens added since.
    expect(evaluations).toHaveLength(MATRIX.length * THEMES.length);
  });

  it("fails on zero combinations instead of passing", () => {
    expect(() => {
      assertCoversMinimum([], MINIMUM);
    }).toThrow(/zero combinations/);
  });

  it("fails and names the minimum combination the matrix lacks", () => {
    expect(() => {
      assertCoversMinimum(MATRIX.slice(1), MINIMUM);
    }).toThrow(new RegExp(MATRIX[0]?.state ?? "unreachable"));
  });
});

describe("the states of the sign in screen [BX109]", () => {
  const SIGN_IN = "/iniciar-sesion";
  const signIn = MATRIX.filter((combination) => combination.route === SIGN_IN);

  it("holds the eight states, each once, so the gate evaluates 16 combinations of the screen in both themes", () => {
    expect(signIn.map((combination) => combination.state)).toEqual([
      "initial",
      "error-summary",
      "credentials-rejected",
      "rate-limited-countdown",
      "service-busy",
      "sending",
      "password-visible",
      "with-notice",
    ]);
    // The gate evaluates the whole matrix, so the count follows it as screens are added.
    expect(assertCoversMinimum(MATRIX, [...MINIMUM, ...signIn])).toHaveLength(MATRIX.length * THEMES.length);
    expect(new Set(MATRIX.map((combination) => `${combination.route} ${combination.state}`)).size).toBe(MATRIX.length);
  });

  it("reaches the states of a failure through the presets of the simulator, and the rest without one", () => {
    const presets = Object.fromEntries(signIn.map((combination) => [combination.state, combination.preset]));

    expect(presets).toEqual({
      initial: undefined,
      "error-summary": undefined,
      "credentials-rejected": "authentication-failed",
      "rate-limited-countdown": "too-many-requests",
      "service-busy": "capacity-exceeded",
      sending: "slow",
      "password-visible": undefined,
      "with-notice": undefined,
    });
  });

  it("keeps the minimum of the part 1 at six combinations until the last screen", () => {
    expect(MINIMUM).toHaveLength(6);
    expect(MINIMUM.some((wanted) => wanted.route === SIGN_IN)).toBe(false);
  });
});

describe("the states of the verification screen [BX109]", () => {
  const VERIFICATION = "/iniciar-sesion/verificacion";
  const verification = MATRIX.filter((combination) => combination.route === VERIFICATION);

  it("holds the three states, each once, all reached through the preset that asks for the second factor", () => {
    expect(verification.map((combination) => [combination.state, combination.preset])).toEqual([
      ["totp", "second-factor-required"],
      ["recovery", "second-factor-required"],
      ["code-rejected", "second-factor-required"],
    ]);
    expect(assertCoversMinimum(MATRIX, [...MINIMUM, ...verification])).toHaveLength(MATRIX.length * THEMES.length);
  });

  it("keeps the minimum of the part 1 at six combinations", () => {
    expect(MINIMUM.some((wanted) => wanted.route === VERIFICATION)).toBe(false);
  });
});

describe("the states of the academic structure", () => {
  const organization = MATRIX.filter((combination) => combination.route.startsWith("/organization/"));

  it("holds the four views ready, the open dialog and its error summary, the empty state and the load error, each once", () => {
    expect(organization.map((combination) => [combination.route, combination.state, combination.preset, combination.opensDialog])).toEqual([
      ["/organization/academic-years", "ready", undefined, undefined],
      ["/organization/modalities", "ready", undefined, undefined],
      ["/organization/grades", "ready", undefined, undefined],
      ["/organization/sections", "ready", undefined, undefined],
      ["/organization/modalities", "dialog-open", undefined, true],
      ["/organization/modalities", "dialog-error-summary", undefined, true],
      ["/organization/academic-years", "empty", "organization-empty", undefined],
      ["/organization/academic-years", "load-error", "organization-server-error", undefined],
    ]);
    expect(assertCoversMinimum(MATRIX, [...MINIMUM, ...organization])).toHaveLength(MATRIX.length * THEMES.length);
    expect(new Set(MATRIX.map((combination) => `${combination.route} ${combination.state}`)).size).toBe(MATRIX.length);
  });
});

describe("the states of the students registry", () => {
  const students = MATRIX.filter((combination) => combination.route === "/students");

  it("holds the list ready, three dialogs (new, edit with a document, errors), the no-match, empty and load-error states, each once", () => {
    expect(students.map((combination) => [combination.state, combination.preset, combination.opensDialog])).toEqual([
      ["ready", undefined, undefined],
      ["dialog-open", undefined, true],
      ["dialog-edit-document", undefined, true],
      ["dialog-error-summary", undefined, true],
      ["dialog-duplicate-code", undefined, true],
      ["no-matches", undefined, undefined],
      ["empty", "students-empty", undefined],
      ["load-error", "students-server-error", undefined],
      ["dialog-guardians", undefined, true],
      ["dialog-guardians-results", undefined, true],
      ["dialog-guardians-split-error", undefined, true],
    ]);
    expect(assertCoversMinimum(MATRIX, [...MINIMUM, ...students])).toHaveLength(MATRIX.length * THEMES.length);
    expect(new Set(MATRIX.map((combination) => `${combination.route} ${combination.state}`)).size).toBe(MATRIX.length);
  });
});

describe("the states of the enrollments", () => {
  const enrollments = MATRIX.filter((combination) => combination.route === "/enrollments");

  it("holds the list ready, the five dialogs (enroll, results, transfer, errors, detail), the empty and the load-error states, each once", () => {
    expect(enrollments.map((combination) => [combination.state, combination.preset, combination.opensDialog])).toEqual([
      ["ready", undefined, undefined],
      ["dialog-enroll", undefined, true],
      ["dialog-enroll-results", undefined, true],
      ["dialog-transfer", undefined, true],
      ["dialog-error-summary", undefined, true],
      ["dialog-detail", undefined, true],
      ["empty", "enrollments-empty", undefined],
      ["load-error", "enrollments-server-error", undefined],
    ]);
    expect(assertCoversMinimum(MATRIX, [...MINIMUM, ...enrollments])).toHaveLength(MATRIX.length * THEMES.length);
    expect(new Set(MATRIX.map((combination) => `${combination.route} ${combination.state}`)).size).toBe(MATRIX.length);
  });
});

describe("the states of the guardians registry", () => {
  const guardians = MATRIX.filter((combination) => combination.route === "/guardians");

  it("holds the list ready, three dialogs (new, edit with a document, errors), the detail, three states of its consents, the no-match, empty and load-error states, each once", () => {
    expect(guardians.map((combination) => [combination.state, combination.preset, combination.opensDialog])).toEqual([
      ["ready", undefined, undefined],
      ["dialog-open", undefined, true],
      ["dialog-edit-document", undefined, true],
      ["dialog-error-summary", undefined, true],
      ["dialog-detail", undefined, true],
      ["dialog-consents", undefined, true],
      ["dialog-consents-form-errors", undefined, true],
      ["dialog-consents-channel-error", undefined, true],
      ["no-matches", undefined, undefined],
      ["empty", "guardians-empty", undefined],
      ["load-error", "guardians-server-error", undefined],
    ]);
    expect(assertCoversMinimum(MATRIX, [...MINIMUM, ...guardians])).toHaveLength(MATRIX.length * THEMES.length);
    expect(new Set(MATRIX.map((combination) => `${combination.route} ${combination.state}`)).size).toBe(MATRIX.length);
  });
});

describe("the states of the imports screen", () => {
  const imports = MATRIX.filter((combination) => combination.route === "/imports");

  it("holds the ready screen, the file error, the simulation with rejections, the confirmation, the result, the file failure, the empty and the load-error states, and the states of the links, of the enrollments and of the history by kind, each once", () => {
    expect(imports.map((combination) => [combination.state, combination.preset, combination.opensDialog])).toEqual([
      ["ready", undefined, undefined],
      ["file-error", undefined, undefined],
      ["simulated-with-rejections", undefined, undefined],
      ["dialog-apply", undefined, true],
      ["result", undefined, undefined],
      ["file-failure", undefined, undefined],
      ["empty", "imports-empty", undefined],
      ["load-error", "imports-server-error", undefined],
      ["kind-guardian-links", undefined, undefined],
      ["links-simulated-with-group-rejections", "imports-empty", undefined],
      ["dialog-apply-links", "imports-empty", true],
      ["links-nothing-to-create", "imports-empty", undefined],
      ["kind-enrollments", undefined, undefined],
      ["enrollments-simulated-with-rejections", "imports-empty", undefined],
      ["dialog-apply-enrollments", "imports-empty", true],
      ["history-filtered-empty", undefined, undefined],
    ]);
    expect(assertCoversMinimum(MATRIX, [...MINIMUM, ...imports])).toHaveLength(MATRIX.length * THEMES.length);
    expect(new Set(MATRIX.map((combination) => `${combination.route} ${combination.state}`)).size).toBe(MATRIX.length);
  });
});

describe("the verdict [BX75]", () => {
  it("names route, state, theme, rule and impact of every blocking violation", () => {
    expect(() => {
      assertNoBlocking([result([violation("button-name", "critical")], "dark")]);
    }).toThrow(
      "/inaccessible [ready] dark: button-name (critical)",
    );
  });

  it("passes when nothing blocks", () => {
    expect(() => {
      assertNoBlocking([result([])]);
    }).not.toThrow();
  });

  it("summarizes the evaluations without the page markup", () => {
    const report = summarizeReport([
      { ...result([violation("button-name", "critical")]), blocking: [{ rule: "button-name", impact: "critical", help: "h", targets: ["#x"] }], reported: [] },
    ]);

    expect(report).toMatchObject({ evaluated: 1, blocking: 1, reported: 0 });
  });
});

describe("the policy and the header", () => {
  const read = (path: string) => readFileSync(join(import.meta.dirname, path), "utf8");

  it("shares the module of the accessibility policy with the story gate [BX73]", () => {
    const stories = read("../../../../packages/ui/src/a11y/run-gate.ts");

    expect(read("./a11y-gate.ts")).toContain('from "@confia/config/a11y"');
    expect(stories).toContain('from "@confia/config/a11y"');
  });

  it("sends the header the simulator reads", () => {
    expect(SCENARIO_HEADER).toBe(SERVER_HEADER);
  });
});
