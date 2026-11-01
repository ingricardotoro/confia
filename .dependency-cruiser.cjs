// Frontend dependency rules (docs/01-arquitectura.md §4; frontend-monorepo-and-contracts-pipeline,
// design.md decision 8; specs/build-integrity, requirement "Reglas de dependencia del frontend").
// Each rule has a permanent deliberate violation under tooling/dependency-fixtures/, and
// tooling/src/dependency-rules.test.ts requires every rule to reject it. The paths match both the
// real tree and that fixture tree, which mirrors apps/ and packages/. The ui-* rules are the layer rules
// of packages/ui (ADR-0018, docs/ui-ux/02 section 2; design decision 10 of design-system-foundations-
// and-a11y); the ESLint configuration of packages/config mirrors them for the editor.
/** @type {import('dependency-cruiser').IConfiguration} */
module.exports = {
  forbidden: [
    {
      name: "packages-never-import-apps",
      comment: "A shared package never depends on an application: the dependency only points inward.",
      severity: "error",
      from: { path: "(^|/)packages/[^/]+/" },
      to: { path: "(^|/)apps/" },
    },
    {
      name: "apps-never-import-other-apps",
      comment: "An application never reaches into another one; what they share goes to packages/.",
      severity: "error",
      from: { path: "(^|/)apps/([^/]+)/" },
      to: { path: "(^|/)apps/", pathNot: "(^|/)apps/$2/" },
    },
    {
      name: "contracts-only-through-public-entry",
      comment:
        "packages/contracts is reached only through its admin and portal entry points, never through " +
        "a generated file or any other internal path.",
      severity: "error",
      from: { pathNot: "(^|/)packages/contracts/" },
      to: { path: "(^|/)packages/contracts/", pathNot: "(^|/)packages/contracts/src/(admin|portal)\\.ts$" },
    },
    {
      name: "portal-never-imports-admin-contracts",
      comment:
        "The portal never compiles against an administrative operation (ADR-0003): it may only use " +
        "the portal contract.",
      severity: "error",
      from: { path: "(^|/)apps/portal-web/" },
      to: { path: "(^|/)packages/contracts/src/(admin\\.ts$|generated/admin/)" },
    },
    {
      name: "ui-atoms-know-only-tokens",
      comment:
        "An atom knows only tokens and other atoms; it never reaches a molecule or the domain layer " +
        "(ADR-0018, docs/ui-ux/02 section 2).",
      severity: "error",
      from: { path: "(^|/)packages/ui/src/atoms/" },
      to: { path: "(^|/)packages/ui/src/(molecules|domain)/" },
    },
    {
      name: "ui-molecules-never-import-domain",
      comment: "A molecule composes atoms; it never reaches the domain layer (ADR-0018, docs/ui-ux/02 section 2).",
      severity: "error",
      from: { path: "(^|/)packages/ui/src/molecules/" },
      to: { path: "(^|/)packages/ui/src/domain/" },
    },
    {
      name: "ui-never-fetches-data",
      comment:
        "packages/ui is pure presentation: it never imports a data library, resolved or not " +
        "(ADR-0018, docs/ui-ux/02 section 2). The container that owns the data lives in the application.",
      severity: "error",
      from: { path: "(^|/)packages/ui/" },
      to: { path: "(^|/node_modules/)(@tanstack/(react-)?query(-core)?|axios|ky|swr)(/|$)" },
    },
    {
      name: "ui-contracts-type-only",
      comment:
        "packages/ui takes only types from the contract: a value would put generated runtime code into " +
        "the design system (ADR-0018, docs/ui-ux/02 section 2). An `import type` is accepted.",
      severity: "error",
      from: { path: "(^|/)packages/ui/" },
      to: { path: "(^|/)packages/contracts/", dependencyTypesNot: ["type-only"] },
    },
  ],
  options: {
    doNotFollow: { path: "node_modules" },
    // Generated output is not authored code: build bundles (dist), coverage and report folders.
    exclude: { path: "(^|/)(node_modules|dist|coverage|reports)/" },
    tsPreCompilationDeps: true,
    enhancedResolveOptions: {
      exportsFields: ["exports"],
      conditionNames: ["import", "require", "node", "default", "types"],
      extensions: [".ts", ".tsx", ".js", ".mjs", ".cjs"],
    },
  },
};
