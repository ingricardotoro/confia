// Frontend dependency rules (docs/01-arquitectura.md §4; frontend-monorepo-and-contracts-pipeline,
// design.md decision 8; specs/build-integrity, requirement "Reglas de dependencia del frontend").
// Each rule has a permanent deliberate violation under tooling/dependency-fixtures/, and
// tooling/src/dependency-rules.test.ts requires every rule to reject it. The paths match both the
// real tree and that fixture tree, which mirrors apps/ and packages/. The ui-* rules are the layer rules
// of packages/ui (ADR-0018, docs/ui-ux/02 section 2; design decision 10 of design-system-foundations-
// and-a11y); the ESLint configuration of packages/config mirrors them for the editor.
// The apps-* and admin-* rules are those of apps/admin-web (admin-web-foundations-and-session, design.md D3).
// Their violations live under tooling/dependency-fixtures/apps/admin-web/src.
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
    {
      name: "apps-use-ui-public-entry",
      comment:
        "An application takes from packages/ui only its public entry: the package index, the catalog " +
        "(@confia/ui/i18n) and the style sheet (@confia/ui/styles.css). Anything else is internal. A " +
        "package name that does not resolve is matched as written.",
      severity: "error",
      from: { path: "(^|/)apps/" },
      to: {
        path: "(^|/)packages/ui/src/|(^|/)@confia/ui/",
        pathNot:
          "(^|/)packages/ui/src/(index\\.ts|i18n/resources\\.ts|tokens/index\\.css)$|^@confia/ui(/i18n|/styles\\.css)?$",
      },
    },
    {
      name: "admin-presentation-is-pure",
      comment:
        "A presentation component of apps/admin-web (the components/ folders) never imports server data, " +
        "the router, the form library or the API client, not even as a type, by a relative path or by an " +
        "alias (CLAUDE.md, frontend: the container owns the data).",
      severity: "error",
      // Two alternatives, not an optional group: the cruiser refuses a nested repetition as unsafe.
      from: { path: "(^|/)apps/admin-web/src/components/|(^|/)apps/admin-web/src/.+/components/" },
      to: {
        path:
          "(^|/node_modules/)@tanstack/(react-query|query-core|react-router|react-form)(/|$)" +
          "|(^|/)apps/admin-web/src/shared/api/|^[@~#]/?shared/api(/|$)",
      },
    },
    {
      name: "admin-shared-never-imports-features",
      comment: "shared/ is below the features and the app shell: shared/api does not know the session.",
      severity: "error",
      from: { path: "(^|/)apps/admin-web/src/shared/" },
      to: { path: "(^|/)apps/admin-web/src/(features|app)/" },
    },
    {
      name: "admin-token-store-is-private",
      comment:
        "Only the session module reads the in-memory token store and the enrollment draft; every other module asks the session.",
      severity: "error",
      from: { path: "(^|/)apps/admin-web/src/", pathNot: "(^|/)apps/admin-web/src/features/identity/session/" },
      to: { path: "(^|/)apps/admin-web/src/features/identity/session/(token-store|enrollment-draft)" },
    },
    {
      name: "admin-session-lifecycle-planner-is-pure",
      comment:
        "The planner and the policy of the session lifecycle import nothing from outside their folder: no React, " +
        "no shared/api, no DOM (admin-web-shell-and-session-lifecycle, design.md H2 and H16). A test file of the " +
        "folder is not the planner and may import its test runner.",
      severity: "error",
      from: {
        path: "(^|/)apps/admin-web/src/features/identity/session/lifecycle/(planner|policy)[^/]*\\.ts$",
        pathNot: "\\.test\\.ts$",
      },
      to: { pathNot: "(^|/)apps/admin-web/src/features/identity/session/lifecycle/" },
    },
    {
      name: "admin-session-lifecycle-is-private",
      comment:
        "Outside src/features/identity/ only session/lifecycle/index.ts is imported, never an internal file of the " +
        "lifecycle folder (admin-web-shell-and-session-lifecycle, design.md H16).",
      severity: "error",
      from: { path: "(^|/)apps/admin-web/src/", pathNot: "(^|/)apps/admin-web/src/features/identity/" },
      to: {
        path: "(^|/)apps/admin-web/src/features/identity/session/lifecycle/",
        pathNot: "(^|/)apps/admin-web/src/features/identity/session/lifecycle/index\\.ts$",
      },
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
