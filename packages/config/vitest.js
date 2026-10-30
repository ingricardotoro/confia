// Shared Vitest base (frontend-monorepo-and-contracts-pipeline, design.md decisions 7 and 9).
// Tests are *.test.ts, plus *.test.tsx for the packages that render React (design-system-foundations-
// and-a11y, task 1.2). The *.spec.ts suffix stays reserved for Playwright:
// IdentityScopeExclusionInventoryTest scans apps/ for it to prove no end-to-end login test exists
// yet.
export const confiaVitest = {
  test: {
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    typecheck: {
      enabled: true,
      include: ["src/**/*.test-d.ts"],
    },
  },
};
