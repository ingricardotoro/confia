# Feature: scholarships-discounts (F2, scholarships cut 2)

Objective: campaign and agreement discounts, the agreement assignment with approval by another person, and the stacking rule in the accrual engine.
Branch: `feat/scholarships-discounts`. Delivery: ask-on-risk (split PRs over 800 lines; verify every slice's frontend locally before pushing). Runners: Maven (`-pl app -am -Djacoco.skip=true`, targeted tests, JDK 25), Vitest, Playwright.

## Specs

- S1: The cut note `docs/cortes/scholarships-discounts.md` is the specification: every rule, endpoint, screen, edge case and acceptance criterion in it.
- S2: Acceptance example, verbatim: "Un estudiante con beca aprobada del 20 % y un descuento de campaña acumulable del 10 % sobre la mensualidad, con una base de L 1,000.00 y un impuesto del 15 %, recibe un cargo con base neta L 720.00, impuesto L 108.00 y total L 828.00. El libro mayor registra L 1,000.00 de ingreso y L 280.00 en becas y descuentos." and "Si el descuento no es acumulable y es del 25 %, se aplica solo el descuento: base neta L 750.00."
- S3: Stacking, user decision verbatim: "En cascada (Recommended)" — scholarship first on the gross base, then each discount on what remains, never below zero; if any benefit is not stackable, only the greatest applies.
- S4: Agreement assignment, user decision verbatim: "Con aprobación (Recommended)" — one person requests, a different person approves; only an approved assignment in force reduces charges.
- S5: Order: backend first, then contract (`admin.openapi.json`, `packages/contracts`), then frontend, then reviews (confia-code-reviewer and confia-security-auditor), then the roadmap line (also fix the F2 «Avance (2026-11-24)» "PR pendiente" to PRs #354 to #361).
- S6: Assumptions pending the owner's confirmation: a scholarship counts as stackable; in the cascade, percentage discounts go before fixed-amount ones, then by code; discounts reuse the `scholarships:*` permissions; tax on the net base (accountant validation list).

## Tasks

| ID | Specs | Route | Status | Evidence |
|---|---|---|---|---|
| T0 | S1, S3, S4 | inline | done: cut note written | |
| T1 | S1, S4, S6 | delegated (context backstop) to confia-backend-dev | pending | |
| T2 | S1, S2, S3, S6 | delegated (context backstop) to confia-backend-dev | pending | |
| T3 | S1, S5 | delegated to confia-frontend-dev | pending | |
| T4 | S5 | delegated reviewers | pending | |
| T5 | S5 | inline | pending | |

T1 = discounts and agreement assignments in the `scholarships` module: migration, domain, use cases, routes, audit, RLS, OpenAPI.
T2 = accrual: stacking in the charges engine (cascade, non-stackable greatest), per-benefit charge detail, ledger total, preview, student charges, simulator.
T3 = contracts and screens. T4 = reviews and fixes. T5 = roadmap line and cut note "Lo construido".

## Log

- L1 (2026-11-26, user): "si, sigamos con los descuentos".
- L2 (2026-11-26, user): stacking "En cascada (Recommended)"; agreement assignment "Con aprobación (Recommended)".
- L3: lessons from the cut-1 merge: each stacked slice must pass frontend CI alone (contract fixtures, route inventory AW59, dependency-cruiser `admin-presentation-is-pure`); do not run Prettier on admin-web src.
