// Frontend dependency rules (docs/01-arquitectura.md §4; frontend-monorepo-and-contracts-pipeline,
// design.md decision 8; specs/build-integrity, requirement "Reglas de dependencia del frontend").
// Each rule has a permanent deliberate violation under tooling/dependency-fixtures/, and
// tooling/src/dependency-rules.test.ts requires every rule to reject it. The paths match both the
// real tree and that fixture tree, which mirrors apps/ and packages/.
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
  ],
  options: {
    doNotFollow: { path: "node_modules" },
    exclude: { path: "(^|/)node_modules/" },
    tsPreCompilationDeps: true,
    enhancedResolveOptions: {
      exportsFields: ["exports"],
      conditionNames: ["import", "require", "node", "default", "types"],
      extensions: [".ts", ".js", ".mjs", ".cjs"],
    },
  },
};
