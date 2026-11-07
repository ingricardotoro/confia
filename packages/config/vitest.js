// Shared Vitest base (frontend-monorepo-and-contracts-pipeline, design.md decisions 7 and 9).
// Tests are *.test.ts, plus *.test.tsx for the packages that render React (design-system-foundations-
// and-a11y, task 1.2). The *.spec.ts and *.spec.js suffixes stay out of the repository:
// IdentityScopeExclusionInventoryTest scans apps/ for them and fails on any. Playwright journeys are
// named *.e2e.ts, and the unit tests of the e2e support code are *.test.ts (admin-web, task 11).
export const confiaVitest = {
  test: {
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    typecheck: {
      enabled: true,
      include: ["src/**/*.test-d.ts"],
    },
  },
};
