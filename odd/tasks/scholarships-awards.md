# Feature: scholarships-awards (F2, scholarships cut 1)

Objective: define scholarships, award them to students with approval by another person, and apply approved awards in the accrual engine.
Branch: `feat/scholarships-awards`. Delivery: ask-on-risk (split PRs over 800 lines). Runners: Maven (`-pl app -am -Djacoco.skip=true`, targeted tests), Vitest, Playwright.

## Specs

- S1: The cut note `docs/cortes/scholarships-awards.md` is the specification: every rule, endpoint, screen, edge case and acceptance criterion in it.
- S2: Acceptance example, verbatim: "Un estudiante con beca aprobada del 25 % sobre la mensualidad, con una base de L 1,100.00 y un impuesto del 15 %, recibe un cargo con base neta L 825.00 e impuesto L 123.75. El libro mayor registra L 1,100.00 de ingreso y L 275.00 en becas."
- S3: Order: backend first, then contract (`admin.openapi.json`, `packages/contracts`), then frontend, then reviews (confia-code-reviewer and confia-security-auditor), then the roadmap line.
- S4: Assumption (pending the accountant, external validation list): tax is computed on the reduced base; the `BENEFITS` account accepts a contra-revenue or expense account type.

## Tasks

| ID | Specs | Route | Status | Evidence |
|---|---|---|---|---|
| T1 | S1 | delegated (context backstop) to confia-backend-dev | partial: backend, contract and permission change done; ScholarshipsIT, AuthorizationEdgeIT and RolesReportEndpointIT not run to completion (run killed for lack of memory) | bb9a094c, c656dadb, dfdcaafa, e86328aa, 1a65686a, 32eba0ba |
| T2 | S1, S2, S4 | delegated (context backstop) to confia-backend-dev | pending | |
| T3 | S1, S3 | delegated to confia-frontend-dev | pending | |
| T4 | S3 | delegated reviewers | pending | |
| T5 | S3 | inline | pending | |

T1 = `scholarships` module: migration, domain (scholarship, award lifecycle, segregation, overlap), use cases, routes, audit, RLS, OpenAPI.
T2 = accrual integration: benefit calculation in the charges engine, charge detail (gross base, award, benefit, net base), `BENEFITS` posting purpose and ledger entry, preview and student charges fields, simulator.
T3 = contracts and screens. T4 = reviews and fixes. T5 = roadmap line and cut note "Lo construido".

## Log

- L1 (2026-11-24, user): "si, sigamos con las becas".
- L2: cut note committed as `68f30a0b`, rebased onto main as `2e32b2d4`.

- L3 (2026-11-24, user): "si, si puede el adminsitrador revocar una beca, con justificacion". The Administrator gains `scholarships:void`; the reason of 20 to 500 characters stays.
