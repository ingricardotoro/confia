import { randomUUID } from "node:crypto";
import type { Page, Route } from "@playwright/test";
import { problem } from "../../src/test/contract-responses.ts";
import type { CombinationId, GateCombination } from "./a11y-report.ts";
import { MOCK_API_URL } from "./scenario-header.ts";

// The states of the screens of the gate (build-integrity, matrix of the gate; BX109): the boot, the signed in page, the route
// that is not found and the sign in screen with its eight states, the verification with three and the enrollment with five. The gate evaluates each one in both themes. The states
// are reached with the simulated API and with page.route, never by calling the code of the app.
const REFRESH = "**/api/v1/auth/sessions/refresh";
const CURRENT = "**/api/v1/auth/sessions/current";

/** An answer that never comes: the screen that waits for it stays where it is. */
const hold = (): Promise<void> => new Promise<void>(() => undefined);
/** A cut of the network. The proxy turns a cut of the server into a 500, so only abort simulates one. */
const cut = (route: Route): Promise<void> => route.abort("failed");

/** Lands on the app with the cookies of a signed in session, as the control of the simulator does. */
const seed = (page: Page) => page.goto(`${MOCK_API_URL}/__control/seed-session`);

const SIGN_IN = "/iniciar-sesion";
const VERIFICATION = "/iniciar-sesion/verificacion";
const ENROLLMENT = "/iniciar-sesion/inscripcion";
const ENROLLMENTS = "**/api/v1/auth/mfa/enrollments";
const IDENTIFIER = "docente.prueba@example.test";

/** Types fictitious credentials and sends the form; the password is made at run time, so none is written here. */
async function submitCredentials(page: Page): Promise<void> {
  await page.goto(SIGN_IN);
  await page.locator("#sign-in-identifier").fill(IDENTIFIER);
  await page.locator("#sign-in-password").fill(randomUUID());
  await page.getByRole("button", { name: "Iniciar sesión" }).click();
}

/** Signs in with the preset that asks for an enrollment and presses Continue on the secret, which leads to the codes. */
async function reachRecoveryCodes(page: Page): Promise<void> {
  await submitCredentials(page);
  await page.getByRole("button", { name: "Continuar" }).click();
  await page.getByRole("heading", { level: 2, name: "Guarde sus códigos de recuperación" }).waitFor();
}

/** Signs in with the preset that asks for the second factor and waits on the verification screen, on its own field. */
async function reachVerification(page: Page): Promise<void> {
  await submitCredentials(page);
  await page.getByRole("textbox", { name: /Código de verificación/ }).waitFor();
}

const ORGANIZATION = "/organization";
const YEARS = `${ORGANIZATION}/academic-years`;
const MODALITIES = `${ORGANIZATION}/modalities`;
const GRADES = `${ORGANIZATION}/grades`;
const SECTIONS = `${ORGANIZATION}/sections`;

/** Lands on a view of the academic structure with the cookies of a signed in session, as the control of the simulator does. */
async function openOrganizationView(page: Page, path: string): Promise<void> {
  await seed(page);
  await page.getByRole("heading", { level: 1, name: "Sesión iniciada." }).waitFor();
  await page.goto(path);
}

/** A view of the academic structure with its rows on the screen: a cell of the seeded table proves it. */
const organizationReady = (route: string, firstCell: string): GateCombination => ({
  route,
  state: "ready",
  arrange: (page) => openOrganizationView(page, route),
  expected: (page) => page.getByRole("cell", { name: firstCell, exact: true }).first(),
});

const STUDENTS = "/students";

/** The registry with its rows on the screen: the surname of the first row of the seed proves it. */
async function openStudentsRoute(page: Page, path: string = STUDENTS): Promise<void> {
  await seed(page);
  await page.getByRole("heading", { level: 1, name: "Sesión iniciada." }).waitFor();
  await page.goto(path);
}

/** Fills the open create dialog of a new student, with the code of the first student of the seed, which is taken. */
async function fillTakenCode(page: Page): Promise<void> {
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel(/^Código/).fill("E-000001");
  await dialog.getByLabel(/^Nombres/).fill("Nuevo");
  await dialog.getByLabel(/^Apellidos/).fill("Estudiante");
  await dialog.getByLabel(/^Fecha de nacimiento/).fill("2013-04-04");
  await dialog.getByLabel(/^Sexo/).selectOption({ label: "Masculino" });
}

const ENROLLMENTS_LIST = "/enrollments";

/** The enrollments list with its rows on the screen: the surname of the first row of the seed proves it. */
async function openEnrollmentsRoute(page: Page, path: string = ENROLLMENTS_LIST): Promise<void> {
  await seed(page);
  await page.getByRole("heading", { level: 1, name: "Sesión iniciada." }).waitFor();
  await page.goto(path);
}

const GUARDIANS = "/guardians";

/** The guardians list with its rows on the screen: the surname of the first row of the seed proves it. */
async function openGuardiansRoute(page: Page, path: string = GUARDIANS): Promise<void> {
  await seed(page);
  await page.getByRole("heading", { level: 1, name: "Sesión iniciada." }).waitFor();
  await page.goto(path);
}

/** The dialog of the guardians of Ana Aguilar (two guardians in the seed), open and loaded. */
async function openLinksDialog(page: Page): Promise<void> {
  await openStudentsRoute(page, `${STUDENTS}?q=Aguilar`);
  await page.getByRole("button", { name: "Encargados de Ana Aguilar" }).click();
  await page.getByRole("dialog").getByText(/Suma guardada actualmente/).waitFor();
}

const CATALOG = "/catalog";
const PRICE_LISTS = "/catalog/price-lists";
/** The seeded lists: with prices (a closed and an open validity of one concept), with none, and archived. */
const PRICE_LIST_DETAIL = `${PRICE_LISTS}/00000000-0000-4000-8000-000000009001`;
const PRICE_LIST_BLANK = `${PRICE_LISTS}/00000000-0000-4000-8000-000000009002`;
const PRICE_LIST_ARCHIVED = `${PRICE_LISTS}/00000000-0000-4000-8000-000000009003`;
const TAX_RATES = "/catalog/tax-rates";
/** The seeded rates: with two validities (one closed, one open), inactive, and active with none. */
const TAX_RATE_DETAIL = `${TAX_RATES}/00000000-0000-4000-8000-000000006001`;
const TAX_RATE_INACTIVE = `${TAX_RATES}/00000000-0000-4000-8000-000000006003`;
const TAX_RATE_BLANK = `${TAX_RATES}/00000000-0000-4000-8000-000000006004`;

const PLANS = "/charges/plans";
/** The seeded plans: an active one with two items, a draft with one, a draft with none, and an archived one. */
const PLAN_ACTIVE = `${PLANS}/00000000-0000-4000-8000-000000009001`;
const PLAN_BLANK = `${PLANS}/00000000-0000-4000-8000-000000009002`;
const PLAN_ARCHIVED = `${PLANS}/00000000-0000-4000-8000-000000009003`;
const PLAN_DRAFT = `${PLANS}/00000000-0000-4000-8000-000000009004`;

/** The payment concepts list with the cookies of a signed in session, as the control of the simulator does. */
async function openCatalogRoute(page: Page, path: string = CATALOG): Promise<void> {
  await seed(page);
  await page.getByRole("heading", { level: 1, name: "Sesión iniciada." }).waitFor();
  await page.goto(path);
}

/** A route of the charges with the cookies of a signed in session: the same way in as the catalog's. */
const openChargesRoute = (page: Page, path: string): Promise<void> => openCatalogRoute(page, path);

/** The cashbox (cashbox-sessions cut): the cashier's page, the list of sessions and the seeded session that awaits its approval. */
const CASHBOX = "/cashbox";
const CASH_SESSIONS = "/cashbox/sessions";
const CASH_SESSION_PENDING = `${CASH_SESSIONS}/00000000-0000-4000-8000-000000400002`;

/** The payments (payments-register cut): the list, the register form and the seeded transfer that another person declared. */
const PAYMENTS = "/payments";
const PAYMENTS_REGISTER = "/payments/register";
const PAYMENT_DECLARED = `${PAYMENTS}/00000000-0000-4000-8000-000000600001`;
const PAYMENT_CONFIRMED = `${PAYMENTS}/00000000-0000-4000-8000-000000600003`;

/** A route of the payments with the cookies of a signed in session: the same way in as the catalog's. */
const openPaymentsRoute = (page: Page, path: string): Promise<void> => openCatalogRoute(page, path);

/** Types a search in the student picker of the register and chooses the first student found, as a cashier would. */
async function chooseStudent(page: Page): Promise<void> {
  await page.getByRole("combobox", { name: "Estudiante" }).fill("Aguilar");
  await page.getByRole("option", { name: /E-000001/ }).click();
  await page.getByRole("table", { name: /Cargos vigentes del estudiante/ }).waitFor();
}

/** The scholarships (scholarships-awards cut): the list, the awards, the seeded pending award another person requested, and the simulator of charges. */
const SCHOLARSHIPS = "/scholarships/list";
const AWARDS = "/scholarships/awards";
const AWARD_PENDING = `${AWARDS}/00000000-0000-4000-8000-0000000f0001`;
const SIMULATOR = "/scholarships/simulator";
/** The discounts (scholarships-discounts cut): the list of discounts. */
const DISCOUNTS = "/scholarships/discounts";
/** The agreement assignments (scholarships-discounts cut): the list, and the seeded pending one another person requested. */
const ASSIGNMENTS = "/scholarships/discount-assignments";
const ASSIGNMENT_PENDING = `${ASSIGNMENTS}/00000000-0000-4000-8000-0000000c0001`;

/** A route of the scholarships with the cookies of a signed in session: the same way in as the catalog's. */
const openScholarshipsRoute = (page: Page, path: string): Promise<void> => openCatalogRoute(page, path);

/** A route of the cashbox with the cookies of a signed in session: the same way in as the catalog's. */
const openCashboxRoute = (page: Page, path: string): Promise<void> => openCatalogRoute(page, path);

/** The generation of charges (charges-accrual cut): the page, the list of runs and the seeded runs (completed with errors, rejected, failed). */
const ACCRUAL = "/charges/accrual";
const ACCRUAL_PLAN = "planId=00000000-0000-4000-8000-000000009001";
const ACCRUAL_FRESH = `${ACCRUAL}?${ACCRUAL_PLAN}&periodStart=2026-05-01`;
const RUNS = `${ACCRUAL}/runs`;
const RUN_WITH_ERRORS = `${RUNS}/00000000-0000-4000-8000-00000000a002`;
const RUN_REJECTED = `${RUNS}/00000000-0000-4000-8000-00000000a003`;
const RUN_FAILED = `${RUNS}/00000000-0000-4000-8000-00000000a004`;

/** The plan and the period come in the address; the preview is asked for with the button. */
async function openPreview(page: Page): Promise<void> {
  await openChargesRoute(page, ACCRUAL_FRESH);
  // `selectOption` waits for the select to be enabled and its options to be there, which is when the periods have loaded.
  await page.getByLabel(/^Período/).selectOption("2026-05-01", { timeout: 20_000 });
  await page.getByRole("button", { name: "Calcular vista previa" }).click();
  await page.getByRole("region", { name: "Resumen de la vista previa" }).waitFor();
}

const IMPORTS = "/imports";
const IMPORT_HEADER = "legacyId,firstName,lastName,birthDate,nationalId,sex";

/** The imports screen with the cookies of a signed in session, as the control of the simulator does. */
async function openImportsRoute(page: Page): Promise<void> {
  await seed(page);
  await page.getByRole("heading", { level: 1, name: "Sesión iniciada." }).waitFor();
  await page.goto(IMPORTS);
  await page.getByRole("heading", { level: 2, name: "Importar datos" }).waitFor();
}

/** Chooses a fictitious file in memory and simulates it: `good` valid rows and the rows given, defective on purpose. */
async function simulateImport(page: Page, good: number, ...defects: string[]): Promise<void> {
  const rows = Array.from({ length: good }, (_, index) => `L${String(index + 1)},Nombre,Apellido,2013-05-01,,F`);
  const buffer = Buffer.from([IMPORT_HEADER, ...rows, ...defects].join("\n"), "utf8");
  await page.getByLabel("Archivo CSV de estudiantes").setInputFiles({ name: "estudiantes.csv", mimeType: "text/csv", buffer });
  await page.getByRole("button", { name: "Simular la importación" }).click();
  await page.getByRole("heading", { level: 3, name: "Resultado de la simulación" }).waitFor();
}

const importDefects = Array.from({ length: 12 }, (_, index) => `L9${String(index)},,Apellido,2013-05-01,,F`);

const LINKS_HEADER = "studentLegacyId,guardianLegacyId,relationship,financialResponsibilityPercent,isPrimaryPayer,isBillingRecipient";
const ENROLLMENTS_HEADER = "studentLegacyId,academicYear,modality,grade,section,enrolledOn";
const fileLabels = {
  students: "Archivo CSV de estudiantes",
  guardians: "Archivo CSV de encargados",
  "guardian-links": "Archivo CSV de vínculos",
  enrollments: "Archivo CSV de matrículas",
} as const;

/** Chooses the kind and a fictitious file in memory, and simulates it. */
async function simulateKind(page: Page, kind: keyof typeof fileLabels, lines: readonly string[]): Promise<void> {
  await page.getByLabel("Qué va a importar").selectOption(kind);
  const buffer = Buffer.from(lines.join("\n"), "utf8");
  await page.getByLabel(fileLabels[kind]).setInputFiles({ name: "archivo.csv", mimeType: "text/csv", buffer });
  await page.getByRole("button", { name: "Simular la importación" }).click();
  await page.getByRole("heading", { level: 3, name: "Resultado de la simulación" }).waitFor();
}

/** Simulates and applies a file, and goes back to the first step. */
async function importKind(page: Page, kind: keyof typeof fileLabels, lines: readonly string[]): Promise<void> {
  await simulateKind(page, kind, lines);
  await page.getByRole("button", { name: "Aplicar la importación" }).click();
  await page.getByRole("dialog").getByRole("button", { name: /^Crear \d+/ }).click();
  await page.getByRole("heading", { level: 3, name: "Importación aplicada" }).waitFor();
  await page.getByRole("button", { name: "Importar otro archivo" }).click();
}

const studentLines = (count: number): string[] => [IMPORT_HEADER, ...Array.from({ length: count }, (_, index) => `S${String(index + 1)},Nombre,Apellido,2013-05-01,,F`)];
const guardianLines = (count: number): string[] => ["legacyId,firstName,lastName", ...Array.from({ length: count }, (_, index) => `G${String(index + 1)},Nombre,Apellido`)];
/** Three students with their guardians: the first valid, the second adding up to 90, the third with a percentage that is not valid. */
const linkLines = [LINKS_HEADER, "S1,G1,FATHER,60,true,true", "S1,G2,MOTHER,40,,", "S2,G1,FATHER,50,,", "S2,G2,MOTHER,40,,", "S3,G1,FATHER,50,,", "S3,G2,MOTHER,50.555,,"];

/** Three students for the enrollments: the first and the third fit, the second names a section that does not exist. */
const enrollmentLines = [ENROLLMENTS_HEADER, "S1,2026,Diurna,Primero,A,2026-02-02", "S2,2026,Diurna,Primero,X,2026-02-02", "S3,2026,Diurna,Primero,B,2026-02-02"];

/** The screen with students already imported, ready to import their enrollments. */
async function openImportsWithStudents(page: Page): Promise<void> {
  await openImportsRoute(page);
  await importKind(page, "students", studentLines(3));
}

/** The screen with students and guardians already imported, ready to import the links. */
async function openImportsWithReferences(page: Page): Promise<void> {
  await openImportsRoute(page);
  await importKind(page, "students", studentLines(3));
  await importKind(page, "guardians", guardianLines(2));
}

/**
 * The 22 combinations of the minimum, written apart from the matrix so a dropped one is noticed (BX74, BX109): the six of the
 * part 1, the eight of the sign in screen, the three of the verification and the five of the enrollment.
 */
export const MINIMUM: readonly CombinationId[] = [
  { route: "/", state: "boot-restoring" },
  { route: "/", state: "boot-failed" },
  { route: "/", state: "signed-in-loading" },
  { route: "/", state: "signed-in-ready" },
  { route: "/", state: "signed-in-error" },
  { route: "/no-existe", state: "not-found" },
  { route: SIGN_IN, state: "initial" },
  { route: SIGN_IN, state: "error-summary" },
  { route: SIGN_IN, state: "credentials-rejected" },
  { route: SIGN_IN, state: "rate-limited-countdown" },
  { route: SIGN_IN, state: "service-busy" },
  { route: SIGN_IN, state: "sending" },
  { route: SIGN_IN, state: "password-visible" },
  { route: SIGN_IN, state: "with-notice" },
  { route: VERIFICATION, state: "totp" },
  { route: VERIFICATION, state: "recovery" },
  { route: VERIFICATION, state: "code-rejected" },
  { route: ENROLLMENT, state: "loading" },
  { route: ENROLLMENT, state: "secret" },
  { route: ENROLLMENT, state: "recovery-codes" },
  { route: ENROLLMENT, state: "first-code" },
  { route: ENROLLMENT, state: "network-error" },
];

export const MATRIX: readonly GateCombination[] = [
  {
    route: "/",
    state: "boot-restoring",
    arrange: async (page) => {
      await page.route(REFRESH, hold);
      await seed(page);
    },
    expected: (page) => page.getByText("Restaurando su sesión."),
  },
  {
    route: "/",
    state: "boot-failed",
    arrange: async (page) => {
      await page.route(REFRESH, cut);
      await seed(page);
    },
    expected: (page) => page.getByRole("button", { name: "Reintentar" }),
  },
  {
    route: "/",
    state: "signed-in-loading",
    arrange: async (page) => {
      await page.route(CURRENT, hold);
      await seed(page);
    },
    expected: (page) => page.getByText("Cargando su sesión."),
  },
  {
    route: "/",
    state: "signed-in-ready",
    arrange: async (page) => {
      await seed(page);
    },
    expected: (page) => page.getByRole("button", { name: "Cerrar sesión" }),
  },
  {
    route: "/",
    state: "signed-in-error",
    arrange: async (page) => {
      await page.route(CURRENT, cut);
      await seed(page);
    },
    expected: (page) => page.getByRole("button", { name: "Reintentar" }),
  },
  {
    route: "/no-existe",
    state: "not-found",
    arrange: async (page) => {
      await page.goto("/no-existe");
    },
    expected: (page) => page.getByRole("heading", { name: "Esta página no existe." }),
  },
  {
    route: SIGN_IN,
    state: "initial",
    arrange: async (page) => {
      await page.goto(SIGN_IN);
    },
    expected: (page) => page.getByRole("button", { name: "Iniciar sesión" }),
  },
  {
    route: SIGN_IN,
    state: "error-summary",
    arrange: async (page) => {
      await page.goto(SIGN_IN);
      await page.getByRole("button", { name: "Iniciar sesión" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 2 campos con errores." }),
  },
  {
    route: SIGN_IN,
    state: "credentials-rejected",
    preset: "authentication-failed",
    arrange: submitCredentials,
    expected: (page) => page.getByRole("alert").filter({ hasText: "El correo o la contraseña no son correctos." }),
  },
  {
    route: SIGN_IN,
    state: "rate-limited-countdown",
    preset: "too-many-requests",
    arrange: submitCredentials,
    expected: (page) => page.getByText(/Puede intentar de nuevo en \d+ s\./),
  },
  {
    route: SIGN_IN,
    state: "service-busy",
    preset: "capacity-exceeded",
    arrange: submitCredentials,
    expected: (page) => page.getByRole("alert").filter({ hasText: "El servicio está ocupado en este momento." }),
  },
  {
    route: SIGN_IN,
    state: "sending",
    preset: "slow",
    arrange: submitCredentials,
    // The answer takes 31 seconds, so the screen stays busy while axe looks at it.
    expected: (page) => page.locator('button[aria-busy="true"]'),
  },
  {
    route: SIGN_IN,
    state: "password-visible",
    arrange: async (page) => {
      await page.goto(SIGN_IN);
      await page.locator("#sign-in-password").fill(randomUUID());
      await page.getByRole("button", { name: "Mostrar contraseña" }).click();
    },
    expected: (page) => page.getByRole("button", { name: "Mostrar contraseña", pressed: true }),
  },
  {
    route: SIGN_IN,
    state: "with-notice",
    arrange: async (page) => {
      // A rejected refresh ends the session and leads here with the notice. The notice of an interrupted
      // verification (preset restricted-expired) is covered by the journeys, where a code is typed.
      const expired = problem("session-expired", 401);
      await page.route(REFRESH, (route) =>
        route.fulfill({ status: expired.status, contentType: expired.contentType ?? "application/problem+json", body: JSON.stringify(expired.body) }),
      );
      await seed(page);
    },
    expected: (page) => page.getByText("Su sesión se cerró por seguridad. Vuelva a iniciar sesión."),
  },
  {
    route: VERIFICATION,
    state: "totp",
    preset: "second-factor-required",
    arrange: submitCredentials,
    expected: (page) => page.getByRole("textbox", { name: /Código de verificación/ }),
  },
  {
    route: VERIFICATION,
    state: "recovery",
    preset: "second-factor-required",
    arrange: async (page) => {
      await reachVerification(page);
      await page.getByRole("button", { name: "Usar un código de recuperación" }).click();
    },
    expected: (page) => page.getByRole("textbox", { name: /Código de recuperación/ }),
  },
  {
    route: VERIFICATION,
    state: "code-rejected",
    preset: "second-factor-required",
    arrange: async (page) => {
      await reachVerification(page);
      await page.getByRole("button", { name: "Usar un código de recuperación" }).click();
      // Not hexadecimal, so no code the simulator hands out can be this one, whatever the run.
      await page.getByRole("textbox", { name: /Código de recuperación/ }).fill("not-a-recovery-code");
      await page.getByRole("button", { name: "Verificar" }).click();
    },
    expected: (page) => page.getByRole("alert").filter({ hasText: "El código de recuperación no es correcto o ya se usó." }),
  },
  organizationReady(YEARS, "Año lectivo 2026"),
  organizationReady(MODALITIES, "Primaria"),
  organizationReady(GRADES, "Primero"),
  organizationReady(SECTIONS, "Primaria · Primero"),
  {
    route: MODALITIES,
    state: "dialog-open",
    opensDialog: true,
    arrange: async (page) => {
      await openOrganizationView(page, MODALITIES);
      await page.getByRole("button", { name: "Nueva modalidad" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Nueva modalidad" }),
  },
  {
    route: MODALITIES,
    state: "dialog-error-summary",
    opensDialog: true,
    arrange: async (page) => {
      await openOrganizationView(page, MODALITIES);
      await page.getByRole("button", { name: "Nueva modalidad" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Guardar" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 2 campos con errores." }),
  },
  {
    route: STUDENTS,
    state: "ready",
    arrange: (page) => openStudentsRoute(page),
    expected: (page) => page.getByRole("cell", { name: "Aguilar", exact: true }).first(),
  },
  {
    route: STUDENTS,
    state: "dialog-open",
    opensDialog: true,
    arrange: async (page) => {
      await openStudentsRoute(page);
      await page.getByRole("button", { name: "Nuevo estudiante" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Nuevo estudiante" }),
  },
  {
    route: STUDENTS,
    state: "dialog-edit-document",
    opensDialog: true,
    arrange: async (page) => {
      await openStudentsRoute(page, `${STUDENTS}?q=P%C3%A9rez`);
      await page.getByRole("button", { name: "Editar Luis Pérez" }).click();
    },
    expected: (page) => page.getByRole("checkbox", { name: "Quitar documento" }),
  },
  {
    route: STUDENTS,
    state: "dialog-error-summary",
    opensDialog: true,
    arrange: async (page) => {
      await openStudentsRoute(page);
      await page.getByRole("button", { name: "Nuevo estudiante" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Guardar" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 4 campos con errores." }),
  },
  {
    route: STUDENTS,
    state: "dialog-duplicate-code",
    opensDialog: true,
    arrange: async (page) => {
      await openStudentsRoute(page);
      await page.getByRole("button", { name: "Nuevo estudiante" }).click();
      await fillTakenCode(page);
      await page.getByRole("dialog").getByRole("button", { name: "Guardar" }).click();
    },
    expected: (page) => page.getByRole("alert").filter({ hasText: "Ya existe un estudiante con ese código." }),
  },
  {
    route: STUDENTS,
    state: "no-matches",
    arrange: (page) => openStudentsRoute(page, `${STUDENTS}?q=zzzz&status=INACTIVE`),
    expected: (page) => page.getByText("Ningún registro coincide con los filtros"),
  },
  {
    route: STUDENTS,
    state: "empty",
    preset: "students-empty",
    arrange: (page) => openStudentsRoute(page),
    expected: (page) => page.getByText("Todavía no hay estudiantes"),
  },
  {
    route: STUDENTS,
    state: "load-error",
    preset: "students-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openStudentsRoute(page),
    expected: (page) => page.getByText("No se pudo cargar la lista de estudiantes"),
  },
  {
    route: ENROLLMENTS_LIST,
    state: "ready",
    arrange: (page) => openEnrollmentsRoute(page),
    expected: (page) => page.getByRole("cell", { name: "Aguilar, Ana", exact: true }).first(),
  },
  {
    route: ENROLLMENTS_LIST,
    state: "dialog-enroll",
    opensDialog: true,
    arrange: async (page) => {
      await openEnrollmentsRoute(page);
      await page.getByRole("button", { name: "Matricular", exact: true }).click();
      // The sections of the year in progress are in the select: the dialog is complete, not loading.
      await page.getByRole("dialog").getByRole("option", { name: "B" }).first().waitFor({ state: "attached" });
    },
    expected: (page) => page.getByRole("dialog", { name: "Matricular estudiante" }),
  },
  {
    route: ENROLLMENTS_LIST,
    state: "dialog-enroll-results",
    opensDialog: true,
    arrange: async (page) => {
      await openEnrollmentsRoute(page);
      await page.getByRole("button", { name: "Matricular", exact: true }).click();
      await page.getByRole("dialog").getByRole("combobox", { name: /Buscar estudiante/ }).fill("Zelaya");
    },
    expected: (page) => page.getByRole("option", { name: "Andrea Zelaya · E-000059" }),
  },
  {
    route: ENROLLMENTS_LIST,
    state: "dialog-transfer",
    opensDialog: true,
    arrange: async (page) => {
      await openEnrollmentsRoute(page);
      await page.getByRole("button", { name: "Trasladar de sección a Ana Aguilar" }).click();
      await page.getByRole("dialog").getByRole("option", { name: "B" }).first().waitFor({ state: "attached" });
    },
    expected: (page) => page.getByRole("dialog", { name: "Trasladar de sección" }),
  },
  {
    route: ENROLLMENTS_LIST,
    state: "dialog-error-summary",
    opensDialog: true,
    arrange: async (page) => {
      await openEnrollmentsRoute(page);
      await page.getByRole("button", { name: "Registrar salida de Ana Aguilar" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Registrar salida" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 1 campo con error." }),
  },
  {
    route: ENROLLMENTS_LIST,
    state: "dialog-detail",
    opensDialog: true,
    arrange: async (page) => {
      // Mateo Lagos is in "Primero A" (the seeded section 00...4001), with a section move in his history.
      await openEnrollmentsRoute(page, `${ENROLLMENTS_LIST}?sectionId=00000000-0000-4000-8000-000000004001`);
      await page.getByRole("button", { name: "Ver detalle de la matrícula de Mateo Lagos" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Detalle de la matrícula" }).getByRole("table"),
  },
  {
    route: ENROLLMENTS_LIST,
    state: "empty",
    preset: "enrollments-empty",
    arrange: (page) => openEnrollmentsRoute(page),
    expected: (page) => page.getByText("Todavía no hay matrículas"),
  },
  {
    route: ENROLLMENTS_LIST,
    state: "load-error",
    preset: "enrollments-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openEnrollmentsRoute(page),
    expected: (page) => page.getByText("No se pudo cargar la lista de matrículas"),
  },
  {
    route: GUARDIANS,
    state: "ready",
    arrange: (page) => openGuardiansRoute(page),
    expected: (page) => page.getByRole("cell", { name: "Aguilar", exact: true }).first(),
  },
  {
    route: GUARDIANS,
    state: "dialog-open",
    opensDialog: true,
    arrange: async (page) => {
      await openGuardiansRoute(page);
      await page.getByRole("button", { name: "Nuevo encargado" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Nuevo encargado" }),
  },
  {
    route: GUARDIANS,
    state: "dialog-edit-document",
    opensDialog: true,
    arrange: async (page) => {
      // Carmen Banegas (P-000003) has a national id in the seed.
      await openGuardiansRoute(page, `${GUARDIANS}?q=Banegas`);
      await page.getByRole("button", { name: "Editar Carmen Banegas" }).click();
    },
    expected: (page) => page.getByRole("checkbox", { name: "Quitar documento" }),
  },
  {
    route: GUARDIANS,
    state: "dialog-error-summary",
    opensDialog: true,
    arrange: async (page) => {
      await openGuardiansRoute(page);
      await page.getByRole("button", { name: "Nuevo encargado" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Guardar" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 2 campos con errores." }),
  },
  {
    route: GUARDIANS,
    state: "dialog-detail",
    opensDialog: true,
    arrange: async (page) => {
      await openGuardiansRoute(page);
      await page.getByRole("button", { name: "Ver detalle de Rosa Aguilar" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Detalle del encargado" }).getByRole("table", { name: "Estudiantes a los que el encargado está vinculado ahora" }),
  },
  {
    route: GUARDIANS,
    state: "dialog-consents",
    opensDialog: true,
    arrange: async (page) => {
      await openGuardiansRoute(page);
      await page.getByRole("button", { name: "Ver detalle de Luis Alvarado" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Registrar consentimiento: Correo electrónico, Institucional" }).click();
      await page.getByRole("dialog").getByRole("textbox", { name: /Versión de la política/ }).fill("2026-01");
      await page.getByRole("dialog").getByRole("combobox", { name: /Evidencia/ }).selectOption({ label: "Formulario en papel firmado" });
      await page.getByRole("dialog").getByRole("button", { name: "Registrar consentimiento", exact: true }).click();
      await page.getByRole("dialog").getByText(/^Se registró el consentimiento/).waitFor();
    },
    expected: (page) => page.getByRole("dialog").getByRole("table", { name: "Eventos de consentimiento, del más reciente al más antiguo" }),
  },
  {
    route: GUARDIANS,
    state: "dialog-consents-form-errors",
    opensDialog: true,
    arrange: async (page) => {
      await openGuardiansRoute(page);
      await page.getByRole("button", { name: "Ver detalle de Luis Alvarado" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Registrar consentimiento: Correo electrónico, Mercadeo" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Registrar consentimiento", exact: true }).click();
    },
    expected: (page) => page.getByRole("dialog").getByRole("group", { name: "Hay 2 campos con errores." }),
  },
  {
    route: GUARDIANS,
    state: "dialog-consents-channel-error",
    opensDialog: true,
    arrange: async (page) => {
      await openGuardiansRoute(page);
      await page.getByRole("button", { name: "Ver detalle de Rosa Aguilar" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Registrar consentimiento: Correo electrónico, Institucional" }).click();
      await page.getByRole("dialog").getByRole("textbox", { name: /Versión de la política/ }).fill("2026-01");
      await page.getByRole("dialog").getByRole("combobox", { name: /Evidencia/ }).selectOption({ label: "Formulario en papel firmado" });
      await page.getByRole("dialog").getByRole("button", { name: "Registrar consentimiento", exact: true }).click();
    },
    expected: (page) => page.getByRole("dialog").getByRole("alert").filter({ hasText: "no tiene registrado el dato de este canal" }),
  },
  {
    route: GUARDIANS,
    state: "no-matches",
    arrange: (page) => openGuardiansRoute(page, `${GUARDIANS}?q=zzzz&status=INACTIVE`),
    expected: (page) => page.getByText("Ningún registro coincide con los filtros"),
  },
  {
    route: GUARDIANS,
    state: "empty",
    preset: "guardians-empty",
    arrange: (page) => openGuardiansRoute(page),
    expected: (page) => page.getByText("Todavía no hay encargados"),
  },
  {
    route: GUARDIANS,
    state: "load-error",
    preset: "guardians-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openGuardiansRoute(page),
    expected: (page) => page.getByText("No se pudo cargar la lista de encargados"),
  },
  {
    route: STUDENTS,
    state: "dialog-guardians",
    opensDialog: true,
    arrange: (page) => openLinksDialog(page),
    expected: (page) => page.getByRole("group", { name: "Rosa Aguilar · P-000001" }),
  },
  {
    route: STUDENTS,
    state: "dialog-guardians-results",
    opensDialog: true,
    arrange: async (page) => {
      await openLinksDialog(page);
      await page.getByRole("dialog").getByRole("combobox", { name: /Agregar un encargado/ }).fill("Banegas");
    },
    expected: (page) => page.getByRole("option", { name: "Carmen Banegas · P-000003" }),
  },
  {
    route: STUDENTS,
    state: "dialog-guardians-split-error",
    opensDialog: true,
    arrange: async (page) => {
      await openLinksDialog(page);
      await page.getByRole("dialog").getByRole("group", { name: "Luis Alvarado · P-000002" }).getByLabel(/^Porcentaje/).fill("30");
      await page.getByRole("dialog").getByRole("button", { name: "Guardar encargados" }).click();
    },
    expected: (page) => page.getByRole("alert").filter({ hasText: "Los porcentajes deben sumar exactamente 100.00." }),
  },
  {
    route: IMPORTS,
    state: "ready",
    arrange: (page) => openImportsRoute(page),
    expected: (page) => page.getByRole("cell", { name: "Aplicada" }),
  },
  {
    route: IMPORTS,
    state: "file-error",
    arrange: async (page) => {
      await openImportsRoute(page);
      await page.getByRole("button", { name: "Simular la importación" }).click();
    },
    expected: (page) => page.getByText("Elija un archivo CSV.").last(),
  },
  {
    route: IMPORTS,
    state: "simulated-with-rejections",
    arrange: async (page) => {
      await openImportsRoute(page);
      await simulateImport(page, 8, ...importDefects);
    },
    expected: (page) => page.getByRole("table", { name: /Filas del archivo con su línea/ }),
  },
  {
    route: IMPORTS,
    state: "dialog-apply",
    opensDialog: true,
    arrange: async (page) => {
      await openImportsRoute(page);
      await simulateImport(page, 8);
      await page.getByRole("button", { name: "Aplicar la importación" }).click();
    },
    expected: (page) => page.getByRole("dialog").getByText("Se crearán 8 estudiantes."),
  },
  {
    route: IMPORTS,
    state: "result",
    arrange: async (page) => {
      await openImportsRoute(page);
      await simulateImport(page, 8);
      await page.getByRole("button", { name: "Aplicar la importación" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Crear 8 estudiantes" }).click();
    },
    expected: (page) => page.getByRole("heading", { level: 3, name: "Importación aplicada" }),
  },
  {
    route: IMPORTS,
    state: "file-failure",
    arrange: async (page) => {
      await openImportsRoute(page);
      const buffer = Buffer.from("legacyId,firstName,hobby\n1,Ana,x", "utf8");
      await page.getByLabel("Archivo CSV de estudiantes").setInputFiles({ name: "estudiantes.csv", mimeType: "text/csv", buffer });
      await page.getByRole("button", { name: "Simular la importación" }).click();
    },
    expected: (page) => page.getByRole("alert").filter({ hasText: "Falta la columna obligatoria" }),
  },
  {
    route: IMPORTS,
    state: "empty",
    preset: "imports-empty",
    arrange: (page) => openImportsRoute(page),
    expected: (page) => page.getByText("Todavía no hay importaciones"),
  },
  {
    route: IMPORTS,
    state: "load-error",
    preset: "imports-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openImportsRoute(page),
    expected: (page) => page.getByText("No se pudo cargar el historial de importaciones"),
  },
  {
    route: IMPORTS,
    state: "kind-guardian-links",
    arrange: async (page) => {
      await openImportsRoute(page);
      await page.getByLabel("Qué va a importar").selectOption("guardian-links");
    },
    expected: (page) => page.getByLabel("Archivo CSV de vínculos"),
  },
  {
    route: IMPORTS,
    state: "links-simulated-with-group-rejections",
    preset: "imports-empty",
    arrange: async (page) => {
      await openImportsWithReferences(page);
      await simulateKind(page, "guardian-links", linkLines);
    },
    expected: (page) => page.getByRole("table", { name: /Filas del archivo con su línea/ }).getByText(/no suman exactamente 100\.00/).first(),
  },
  {
    route: IMPORTS,
    state: "dialog-apply-links",
    opensDialog: true,
    preset: "imports-empty",
    arrange: async (page) => {
      await openImportsWithReferences(page);
      await simulateKind(page, "guardian-links", linkLines);
      await page.getByRole("button", { name: "Aplicar la importación" }).click();
    },
    expected: (page) => page.getByRole("dialog").getByText("Se crearán 2 vínculos."),
  },
  {
    route: IMPORTS,
    state: "links-nothing-to-create",
    preset: "imports-empty",
    arrange: async (page) => {
      await openImportsWithReferences(page);
      await importKind(page, "guardian-links", [LINKS_HEADER, "S1,G1,FATHER,60,true,true", "S1,G2,MOTHER,40,,"]);
      await simulateKind(page, "guardian-links", [LINKS_HEADER, "S1,G1,FATHER,60,true,true", "S1,G2,MOTHER,40,,"]);
    },
    expected: (page) => page.getByText("No hay nada que crear"),
  },
  {
    route: IMPORTS,
    state: "kind-enrollments",
    arrange: async (page) => {
      await openImportsRoute(page);
      await page.getByLabel("Qué va a importar").selectOption("enrollments");
    },
    expected: (page) => page.getByLabel("Archivo CSV de matrículas"),
  },
  {
    route: IMPORTS,
    state: "enrollments-simulated-with-rejections",
    preset: "imports-empty",
    arrange: async (page) => {
      await openImportsWithStudents(page);
      await simulateKind(page, "enrollments", enrollmentLines);
    },
    expected: (page) => page.getByRole("table", { name: /Filas del archivo con su línea/ }).getByText(/No existe una sección con ese año lectivo/).first(),
  },
  {
    route: IMPORTS,
    state: "dialog-apply-enrollments",
    opensDialog: true,
    preset: "imports-empty",
    arrange: async (page) => {
      await openImportsWithStudents(page);
      await simulateKind(page, "enrollments", enrollmentLines);
      await page.getByRole("button", { name: "Aplicar la importación" }).click();
    },
    expected: (page) => page.getByRole("dialog").getByText("Se crearán 2 matrículas."),
  },
  {
    route: IMPORTS,
    state: "history-filtered-empty",
    arrange: async (page) => {
      await openImportsRoute(page);
      await page.getByLabel("Tipo de importación").selectOption("guardian-links");
    },
    expected: (page) => page.getByText("Ningún registro coincide con los filtros"),
  },
  {
    route: IMPORTS,
    state: "reconciliation-difference",
    arrange: async (page) => {
      await openImportsRoute(page);
      await page.getByLabel(/^Referencia del listado oficial/).fill("Oficio 0123-2026");
      await page.getByLabel(/^Total de matrículas del listado oficial/).fill("5");
    },
    expected: (page) => page.getByText(/^El sistema tiene .* que el listado oficial\.$/),
  },
  {
    route: IMPORTS,
    state: "reconciliation-form-errors",
    arrange: async (page) => {
      await openImportsRoute(page);
      await page.getByRole("button", { name: "Firmar acta" }).click();
    },
    expected: (page) => page.getByRole("group", { name: /campos? con errores?/ }),
  },
  {
    route: IMPORTS,
    state: "reconciliation-signed",
    preset: "imports-empty",
    arrange: async (page) => {
      await openImportsRoute(page);
      await page.getByLabel(/^Referencia del listado oficial/).fill("Oficio 0123-2026");
      await page.getByLabel(/^Total de matrículas del listado oficial/).fill("5");
      await page.getByRole("button", { name: "Firmar acta" }).click();
    },
    expected: (page) => page.getByRole("table", { name: /Actas de conciliación firmadas/ }).getByText("Oficio 0123-2026"),
  },
  {
    route: YEARS,
    state: "empty",
    preset: "organization-empty",
    arrange: (page) => openOrganizationView(page, YEARS),
    expected: (page) => page.getByText("Todavía no hay años lectivos"),
  },
  {
    route: YEARS,
    state: "load-error",
    preset: "organization-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openOrganizationView(page, YEARS),
    expected: (page) => page.getByText("No se pudo cargar la lista de años lectivos"),
  },
  {
    route: ENROLLMENT,
    state: "loading",
    preset: "enrollment-required",
    arrange: async (page) => {
      await page.route(ENROLLMENTS, hold);
      await submitCredentials(page);
    },
    expected: (page) => page.getByText("Preparando su verificación en dos pasos."),
  },
  {
    route: ENROLLMENT,
    state: "secret",
    preset: "enrollment-required",
    arrange: submitCredentials,
    expected: (page) => page.getByRole("img", { name: "Código QR para su aplicación de autenticación" }),
  },
  {
    route: ENROLLMENT,
    state: "recovery-codes",
    preset: "enrollment-required",
    arrange: reachRecoveryCodes,
    expected: (page) => page.getByRole("checkbox", { name: /He guardado mis códigos de recuperación/ }),
  },
  {
    route: ENROLLMENT,
    state: "first-code",
    preset: "enrollment-required",
    arrange: async (page) => {
      await reachRecoveryCodes(page);
      await page.getByRole("checkbox", { name: /He guardado mis códigos de recuperación/ }).click();
      await page.getByRole("button", { name: "Continuar" }).click();
    },
    expected: (page) => page.getByRole("textbox", { name: /Código de verificación/ }),
  },
  {
    route: ENROLLMENT,
    state: "network-error",
    preset: "enrollment-required",
    arrange: async (page) => {
      await page.route(ENROLLMENTS, cut);
      await submitCredentials(page);
    },
    expected: (page) => page.getByRole("alert").filter({ hasText: "No se pudo preparar la verificación en dos pasos." }),
  },
  {
    route: CATALOG,
    state: "ready",
    arrange: (page) => openCatalogRoute(page),
    expected: (page) => page.getByRole("cell", { name: "Colegiatura", exact: true }).first(),
  },
  {
    route: CATALOG,
    state: "dialog-open",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page);
      await page.getByRole("button", { name: "Nuevo concepto" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Nuevo concepto de pago" }),
  },
  {
    route: CATALOG,
    state: "dialog-edit",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page);
      await page.getByRole("button", { name: "Editar Colegiatura" }).click();
    },
    expected: (page) => page.getByRole("checkbox", { name: "Activo" }),
  },
  {
    route: CATALOG,
    state: "dialog-error-summary",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page);
      await page.getByRole("button", { name: "Nuevo concepto" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Guardar" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 3 campos con errores." }),
  },
  {
    route: CATALOG,
    state: "dialog-duplicate-code",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page);
      await page.getByRole("button", { name: "Nuevo concepto" }).click();
      const dialog = page.getByRole("dialog");
      await dialog.getByLabel(/^Código/).fill("col");
      await dialog.getByLabel(/^Nombre/).fill("Otra colegiatura");
      await dialog.getByLabel(/^Categoría/).selectOption({ label: "Otro" });
      await dialog.getByRole("button", { name: "Guardar" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 1 campo con error." }),
  },
  {
    // The field of the tax rate that a taxable concept asks for, with a required choice and its help.
    route: CATALOG,
    state: "dialog-taxable",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page);
      await page.getByRole("button", { name: "Nuevo concepto" }).click();
      const dialog = page.getByRole("dialog");
      // The fields are filled first: leaving an empty one shows its error and moves the checkbox before it is pressed.
      await dialog.getByLabel(/^Código/).fill("COL");
      await dialog.getByLabel(/^Nombre/).fill("Colegiatura");
      await dialog.getByLabel(/^Categoría/).selectOption({ label: "Colegiatura" });
      await dialog.getByRole("checkbox", { name: "Gravado" }).check();
    },
    expected: (page) => page.getByRole("dialog").getByLabel(/^Tasa de impuesto/),
  },
  {
    route: CATALOG,
    state: "no-matches",
    arrange: (page) => openCatalogRoute(page, `${CATALOG}?category=FINE&active=false`),
    expected: (page) => page.getByText("Ningún registro coincide con los filtros"),
  },
  {
    route: CATALOG,
    state: "empty",
    preset: "catalog-empty",
    arrange: (page) => openCatalogRoute(page),
    expected: (page) => page.getByText("Todavía no hay conceptos de pago"),
  },
  {
    route: CATALOG,
    state: "load-error",
    preset: "catalog-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openCatalogRoute(page),
    expected: (page) => page.getByText("No se pudo cargar la lista de conceptos de pago"),
  },
  // The price lists (catalog-price-lists cut): the list, the detail of a list with prices, of a list with none and of an
  // archived one, and the dialogs of a new list and of a new price or validity.
  {
    route: PRICE_LISTS,
    state: "ready",
    arrange: (page) => openCatalogRoute(page, PRICE_LISTS),
    expected: (page) => page.getByRole("link", { name: "2026 — Institución", exact: true }),
  },
  {
    route: PRICE_LISTS,
    state: "dialog-open",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page, PRICE_LISTS);
      await page.getByRole("button", { name: "Nueva lista de precios" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Nueva lista de precios" }),
  },
  {
    route: PRICE_LISTS,
    state: "dialog-error-summary",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page, PRICE_LISTS);
      await page.getByRole("button", { name: "Nueva lista de precios" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Guardar" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 2 campos con errores." }),
  },
  {
    route: PRICE_LISTS,
    state: "no-matches",
    arrange: (page) => openCatalogRoute(page, `${PRICE_LISTS}?scope=MODALITY&status=ARCHIVED`),
    expected: (page) => page.getByText("Ningún registro coincide con los filtros"),
  },
  {
    route: PRICE_LISTS,
    state: "empty",
    preset: "catalog-price-lists-empty",
    arrange: (page) => openCatalogRoute(page, PRICE_LISTS),
    expected: (page) => page.getByText("Todavía no hay listas de precios"),
  },
  {
    route: PRICE_LISTS,
    state: "load-error",
    preset: "catalog-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openCatalogRoute(page, PRICE_LISTS),
    expected: (page) => page.getByText("No se pudieron cargar las listas de precios"),
  },
  {
    route: PRICE_LIST_DETAIL,
    state: "ready",
    arrange: (page) => openCatalogRoute(page, PRICE_LIST_DETAIL),
    expected: (page) => page.getByRole("table", { name: "Vigencias de Colegiatura" }),
  },
  {
    route: PRICE_LIST_DETAIL,
    state: "dialog-add-price",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page, PRICE_LIST_DETAIL);
      await page.getByRole("button", { name: "Nuevo precio / nueva vigencia" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Nuevo precio / nueva vigencia" }),
  },
  {
    route: PRICE_LIST_DETAIL,
    state: "dialog-error-summary",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page, PRICE_LIST_DETAIL);
      await page.getByRole("button", { name: "Nuevo precio / nueva vigencia" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Guardar precio" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 3 campos con errores." }),
  },
  {
    route: PRICE_LIST_DETAIL,
    state: "dialog-overlap",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page, PRICE_LIST_DETAIL);
      await page.getByRole("button", { name: "Nuevo precio / nueva vigencia" }).click();
      const dialog = page.getByRole("dialog");
      await dialog.getByLabel(/^Concepto/).selectOption({ label: "Colegiatura" });
      await dialog.getByLabel(/^Importe/).fill("1700.00");
      // Inside the open validity of the seed: the server refuses it with 409.
      await dialog.getByLabel(/^Vigente desde/).fill("2026-08-01");
      await dialog.getByLabel(/^Vigente hasta/).fill("2026-08-31");
      await dialog.getByRole("button", { name: "Guardar precio" }).click();
    },
    expected: (page) => page.getByRole("dialog").getByRole("alert").filter({ hasText: "ya tiene un precio vigente" }),
  },
  {
    route: PRICE_LIST_BLANK,
    state: "empty",
    arrange: (page) => openCatalogRoute(page, PRICE_LIST_BLANK),
    expected: (page) => page.getByText("Esta lista todavía no tiene precios"),
  },
  {
    route: PRICE_LIST_ARCHIVED,
    state: "archived",
    arrange: (page) => openCatalogRoute(page, PRICE_LIST_ARCHIVED),
    expected: (page) => page.getByText(/está archivada y no admite precios nuevos/),
  },
  {
    route: PRICE_LIST_DETAIL,
    state: "load-error",
    preset: "catalog-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openCatalogRoute(page, PRICE_LIST_DETAIL),
    expected: (page) => page.getByText("No se pudo cargar la lista de precios"),
  },
  // The tax rates (catalog-tax-rates cut): the list, the detail of a rate with its history, of one with none and of an
  // inactive one, and the dialogs of a new rate and of a new validity.
  {
    route: TAX_RATES,
    state: "ready",
    arrange: (page) => openCatalogRoute(page, TAX_RATES),
    expected: (page) => page.getByRole("link", { name: "IMP-A", exact: true }),
  },
  {
    route: TAX_RATES,
    state: "dialog-open",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page, TAX_RATES);
      await page.getByRole("button", { name: "Nueva tasa" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Nueva tasa de impuesto" }),
  },
  {
    route: TAX_RATES,
    state: "dialog-edit",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page, TAX_RATES);
      await page.getByRole("button", { name: "Editar Impuesto de prueba A" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Editar tasa de impuesto" }),
  },
  {
    route: TAX_RATES,
    state: "dialog-error-summary",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page, TAX_RATES);
      await page.getByRole("button", { name: "Nueva tasa" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Guardar" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 2 campos con errores." }),
  },
  {
    route: TAX_RATES,
    state: "dialog-duplicate-code",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page, TAX_RATES);
      await page.getByRole("button", { name: "Nueva tasa" }).click();
      const dialog = page.getByRole("dialog");
      await dialog.getByLabel(/^Código/).fill("imp-a");
      await dialog.getByLabel(/^Nombre/).fill("Otra tasa");
      await dialog.getByRole("button", { name: "Guardar" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 1 campo con error." }),
  },
  {
    route: TAX_RATES,
    state: "no-matches",
    preset: "catalog-tax-rates-empty",
    arrange: (page) => openCatalogRoute(page, `${TAX_RATES}?active=true`),
    expected: (page) => page.getByText("Ningún registro coincide con los filtros"),
  },
  {
    route: TAX_RATES,
    state: "empty",
    preset: "catalog-tax-rates-empty",
    arrange: (page) => openCatalogRoute(page, TAX_RATES),
    expected: (page) => page.getByText("Todavía no hay tasas de impuesto"),
  },
  {
    route: TAX_RATES,
    state: "load-error",
    preset: "catalog-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openCatalogRoute(page, TAX_RATES),
    expected: (page) => page.getByText("No se pudo cargar la lista de tasas de impuesto"),
  },
  {
    route: TAX_RATE_DETAIL,
    state: "ready",
    arrange: (page) => openCatalogRoute(page, TAX_RATE_DETAIL),
    expected: (page) => page.getByRole("table", { name: "Vigencias de Impuesto de prueba A" }),
  },
  {
    route: TAX_RATE_DETAIL,
    state: "dialog-add-period",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page, TAX_RATE_DETAIL);
      await page.getByRole("button", { name: "Nueva vigencia" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Nueva vigencia" }),
  },
  {
    route: TAX_RATE_DETAIL,
    state: "dialog-error-summary",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page, TAX_RATE_DETAIL);
      await page.getByRole("button", { name: "Nueva vigencia" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Guardar vigencia" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 2 campos con errores." }),
  },
  {
    route: TAX_RATE_DETAIL,
    state: "dialog-overlap",
    opensDialog: true,
    arrange: async (page) => {
      await openCatalogRoute(page, TAX_RATE_DETAIL);
      await page.getByRole("button", { name: "Nueva vigencia" }).click();
      const dialog = page.getByRole("dialog");
      await dialog.getByLabel(/^Porcentaje/).fill("5");
      // Inside the open validity of the seed: the server refuses it with 409.
      await dialog.getByLabel(/^Vigente desde/).fill("2026-08-01");
      await dialog.getByLabel(/^Vigente hasta/).fill("2026-08-31");
      await dialog.getByRole("button", { name: "Guardar vigencia" }).click();
    },
    expected: (page) => page.getByRole("dialog").getByRole("alert").filter({ hasText: "ya tiene una vigencia" }),
  },
  {
    route: TAX_RATE_BLANK,
    state: "empty",
    arrange: (page) => openCatalogRoute(page, TAX_RATE_BLANK),
    expected: (page) => page.getByText("Esta tasa todavía no tiene vigencias"),
  },
  {
    route: TAX_RATE_INACTIVE,
    state: "inactive",
    arrange: (page) => openCatalogRoute(page, TAX_RATE_INACTIVE),
    expected: (page) => page.getByText(/está inactiva y no admite vigencias nuevas/),
  },
  {
    route: TAX_RATE_DETAIL,
    state: "load-error",
    preset: "catalog-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openCatalogRoute(page, TAX_RATE_DETAIL),
    expected: (page) => page.getByText("No se pudo cargar la tasa de impuesto"),
  },
  // The charge plans (charges-plans cut): the list, the detail of a draft with an item, of a draft with none, of an active plan
  // and of an archived one, and the dialogs of a new plan, of a new item and of each confirmation.
  {
    route: PLANS,
    state: "ready",
    arrange: (page) => openChargesRoute(page, PLANS),
    expected: (page) => page.getByRole("link", { name: "Primaria 2026", exact: true }),
  },
  {
    route: PLANS,
    state: "dialog-open",
    opensDialog: true,
    arrange: async (page) => {
      await openChargesRoute(page, PLANS);
      await page.getByRole("button", { name: "Nuevo plan de cobro" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Nuevo plan de cobro" }),
  },
  {
    route: PLANS,
    state: "dialog-error-summary",
    opensDialog: true,
    arrange: async (page) => {
      await openChargesRoute(page, PLANS);
      await page.getByRole("button", { name: "Nuevo plan de cobro" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Guardar" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 4 campos con errores." }),
  },
  {
    route: PLANS,
    state: "no-matches",
    arrange: (page) => openChargesRoute(page, `${PLANS}?targetScope=MODALITY&status=ARCHIVED`),
    expected: (page) => page.getByText("Ningún registro coincide con los filtros"),
  },
  {
    route: PLANS,
    state: "empty",
    preset: "charges-empty",
    arrange: (page) => openChargesRoute(page, PLANS),
    expected: (page) => page.getByText("Todavía no hay planes de cobro"),
  },
  {
    route: PLANS,
    state: "load-error",
    preset: "charges-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openChargesRoute(page, PLANS),
    expected: (page) => page.getByText("No se pudieron cargar los planes de cobro"),
  },
  {
    route: PLAN_DRAFT,
    state: "ready",
    arrange: (page) => openChargesRoute(page, PLAN_DRAFT),
    expected: (page) => page.getByRole("table", { name: "Conceptos del plan Segundo grado 2026" }),
  },
  {
    route: PLAN_DRAFT,
    state: "dialog-add-item",
    opensDialog: true,
    arrange: async (page) => {
      await openChargesRoute(page, PLAN_DRAFT);
      await page.getByRole("button", { name: "Agregar concepto" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Agregar concepto" }),
  },
  {
    route: PLAN_DRAFT,
    state: "dialog-error-summary",
    opensDialog: true,
    arrange: async (page) => {
      await openChargesRoute(page, PLAN_DRAFT);
      await page.getByRole("button", { name: "Agregar concepto" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Agregar concepto" }).click();
    },
    expected: (page) => page.getByRole("group", { name: "Hay 3 campos con errores." }),
  },
  {
    route: PLAN_DRAFT,
    state: "dialog-first-of-month",
    opensDialog: true,
    arrange: async (page) => {
      await openChargesRoute(page, PLAN_DRAFT);
      await page.getByRole("button", { name: "Agregar concepto" }).click();
      const dialog = page.getByRole("dialog");
      await dialog.getByLabel(/^Concepto/).selectOption({ label: "Matrícula anual" });
      await dialog.getByLabel(/^Cantidad de períodos/).fill("1");
      // Not the first of a month: refused before sending, with the message that says what to do.
      await dialog.getByLabel(/^Primer día del primer período/).fill("2026-02-15");
      await dialog.getByRole("button", { name: "Agregar concepto" }).click();
    },
    expected: (page) => page.getByRole("dialog").getByText(/Elija el primer día de un mes/).first(),
  },
  {
    route: PLAN_DRAFT,
    state: "dialog-edit",
    opensDialog: true,
    arrange: async (page) => {
      await openChargesRoute(page, PLAN_DRAFT);
      await page.getByRole("button", { name: "Editar plan Segundo grado 2026" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Editar plan de cobro" }),
  },
  {
    route: PLAN_DRAFT,
    state: "dialog-confirm-activate",
    opensDialog: true,
    arrange: async (page) => {
      await openChargesRoute(page, PLAN_DRAFT);
      await page.getByRole("button", { name: "Activar plan Segundo grado 2026" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Activar el plan «Segundo grado 2026»" }),
  },
  {
    route: PLAN_DRAFT,
    state: "dialog-confirm-remove",
    opensDialog: true,
    arrange: async (page) => {
      await openChargesRoute(page, PLAN_DRAFT);
      await page.getByRole("button", { name: "Quitar Colegiatura del plan" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Quitar «Colegiatura» del plan" }),
  },
  {
    route: PLAN_BLANK,
    state: "empty",
    arrange: (page) => openChargesRoute(page, PLAN_BLANK),
    expected: (page) => page.getByText("Este plan todavía no tiene conceptos"),
  },
  {
    route: PLAN_ACTIVE,
    state: "active",
    arrange: (page) => openChargesRoute(page, PLAN_ACTIVE),
    expected: (page) => page.getByText(/Este plan está activo y congelado/),
  },
  {
    route: PLAN_ACTIVE,
    state: "dialog-confirm-archive",
    opensDialog: true,
    arrange: async (page) => {
      await openChargesRoute(page, PLAN_ACTIVE);
      await page.getByRole("button", { name: "Archivar plan Primaria 2026" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Archivar el plan «Primaria 2026»" }),
  },
  {
    route: PLAN_ARCHIVED,
    state: "archived",
    arrange: (page) => openChargesRoute(page, PLAN_ARCHIVED),
    expected: (page) => page.getByText(/Este plan está archivado/),
  },
  {
    route: PLAN_DRAFT,
    state: "load-error",
    preset: "charges-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openChargesRoute(page, PLAN_DRAFT),
    expected: (page) => page.getByText("No se pudo cargar el plan de cobro"),
  },
  // The generation of charges (charges-accrual cut): the page idle, with a preview, with its confirmation, blocked, with nothing to
  // confirm, with no active plan and with its errors; the list of runs; and the run in the states a person meets.
  {
    route: ACCRUAL,
    state: "idle",
    arrange: (page) => openChargesRoute(page, ACCRUAL),
    expected: (page) => page.getByText("Elija un plan y un período"),
  },
  {
    route: ACCRUAL,
    state: "preview-ready",
    arrange: (page) => openPreview(page),
    expected: (page) => page.getByRole("table", { name: "Renglones de la vista previa: un estudiante y un concepto por renglón" }),
  },
  {
    route: ACCRUAL,
    state: "dialog-confirm",
    opensDialog: true,
    arrange: async (page) => {
      await openPreview(page);
      await page.getByRole("button", { name: "Confirmar generación" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "¿Generar los cargos de Primaria 2026?" }),
  },
  {
    route: ACCRUAL,
    state: "dialog-confirm-refused",
    preset: "charges-accrual-in-progress",
    opensDialog: true,
    arrange: async (page) => {
      await openPreview(page);
      await page.getByRole("button", { name: "Confirmar generación" }).click();
      await page.getByRole("dialog").getByRole("button", { name: "Generar cargos" }).click();
    },
    expected: (page) => page.getByRole("dialog").getByRole("alert"),
  },
  {
    route: ACCRUAL,
    state: "blocked",
    preset: "charges-accrual-blocked",
    arrange: (page) => openPreview(page),
    expected: (page) => page.getByText("La generación no se puede confirmar todavía"),
  },
  {
    route: ACCRUAL,
    state: "nothing-to-confirm",
    arrange: async (page) => {
      await openChargesRoute(page, `${ACCRUAL}?${ACCRUAL_PLAN}&periodStart=2026-01-01`);
      await page.getByLabel(/^Período/).selectOption("2026-01-01", { timeout: 20_000 });
      await page.getByRole("button", { name: "Calcular vista previa" }).click();
    },
    expected: (page) => page.getByText(/no hay cargos nuevos que crear/),
  },
  {
    route: ACCRUAL,
    state: "no-active-plans",
    preset: "charges-empty",
    arrange: (page) => openChargesRoute(page, ACCRUAL),
    expected: (page) => page.getByText("No hay planes activos"),
  },
  {
    route: ACCRUAL,
    state: "preview-error",
    preset: "charges-accrual-forbidden",
    arrange: async (page) => {
      await openChargesRoute(page, ACCRUAL_FRESH);
      await page.getByLabel(/^Período/).selectOption("2026-05-01", { timeout: 20_000 });
      await page.getByRole("button", { name: "Calcular vista previa" }).click();
    },
    expected: (page) => page.getByText("No se pudo calcular la vista previa"),
  },
  {
    route: ACCRUAL,
    state: "load-error",
    preset: "charges-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openChargesRoute(page, ACCRUAL),
    expected: (page) => page.getByText("No se pudieron cargar los planes activos"),
  },
  {
    route: RUNS,
    state: "ready",
    arrange: (page) => openChargesRoute(page, RUNS),
    expected: (page) => page.getByRole("link", { name: "2026-04", exact: true }),
  },
  {
    route: RUNS,
    state: "no-matches",
    arrange: (page) => openChargesRoute(page, `${RUNS}?planId=00000000-0000-4000-8000-000000009002`),
    expected: (page) => page.getByText("Ningún registro coincide con los filtros"),
  },
  {
    route: RUNS,
    state: "empty",
    preset: "charges-accrual-empty",
    arrange: (page) => openChargesRoute(page, RUNS),
    expected: (page) => page.getByText("Todavía no hay ejecuciones"),
  },
  {
    route: RUNS,
    state: "load-error",
    preset: "charges-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openChargesRoute(page, RUNS),
    expected: (page) => page.getByText("No se pudieron cargar las ejecuciones"),
  },
  {
    route: RUN_WITH_ERRORS,
    state: "completed-with-errors",
    arrange: (page) => openChargesRoute(page, RUN_WITH_ERRORS),
    expected: (page) => page.getByRole("table", { name: "Renglones de la ejecución: un estudiante y un concepto por renglón" }),
  },
  {
    route: RUN_REJECTED,
    state: "rejected",
    arrange: (page) => openChargesRoute(page, RUN_REJECTED),
    expected: (page) => page.getByText(/Los datos cambiaron desde la vista previa/),
  },
  {
    route: RUN_FAILED,
    state: "failed",
    arrange: (page) => openChargesRoute(page, RUN_FAILED),
    expected: (page) => page.getByText(/El sistema agotó sus reintentos/),
  },
  {
    route: RUN_WITH_ERRORS,
    state: "load-error",
    preset: "charges-server-error",
    // The page retries a read twice by itself (1 s and 2 s) and the simulator fails exactly those three reads, so the state shows after about 3 s.
    arrange: (page) => openChargesRoute(page, RUN_WITH_ERRORS),
    expected: (page) => page.getByText("No se pudo cargar la ejecución"),
  },
  // The cashbox (cashbox-sessions cut): the cashier's own page with no session (the form to open one), with an open session, its close dialog and
  // its movement dialog, and a read that fails; the list of sessions, empty and with rows; and the report of a close that awaits its approval, with
  // the approval dialog open.
  {
    route: CASHBOX,
    state: "no-session",
    preset: "cashbox-empty",
    arrange: (page) => openCashboxRoute(page, CASHBOX),
    expected: (page) => page.getByRole("button", { name: "Abrir caja" }),
  },
  {
    route: CASHBOX,
    state: "open-session",
    preset: "cashbox-open",
    arrange: (page) => openCashboxRoute(page, CASHBOX),
    expected: (page) => page.getByRole("table", { name: "Movimientos de la caja" }),
  },
  {
    route: CASHBOX,
    state: "close-dialog",
    preset: "cashbox-open",
    opensDialog: true,
    arrange: async (page) => {
      await openCashboxRoute(page, CASHBOX);
      await page.getByRole("button", { name: "Cerrar caja" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Cerrar caja" }),
  },
  {
    route: CASHBOX,
    state: "movement-dialog",
    preset: "cashbox-open",
    opensDialog: true,
    arrange: async (page) => {
      await openCashboxRoute(page, CASHBOX);
      await page.getByRole("button", { name: "Registrar retiro" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Registrar retiro" }),
  },
  {
    route: CASHBOX,
    state: "load-error",
    preset: "cashbox-server-error",
    arrange: (page) => openCashboxRoute(page, CASHBOX),
    expected: (page) => page.getByText("No se pudo cargar su caja"),
  },
  {
    route: CASH_SESSIONS,
    state: "ready",
    arrange: (page) => openCashboxRoute(page, CASH_SESSIONS),
    expected: (page) => page.getByRole("table", { name: "Sesiones de caja" }).getByRole("row").nth(1),
  },
  {
    route: CASH_SESSIONS,
    state: "empty",
    preset: "cashbox-empty",
    arrange: (page) => openCashboxRoute(page, CASH_SESSIONS),
    expected: (page) => page.getByText("Todavía no hay sesiones de caja"),
  },
  {
    route: CASH_SESSION_PENDING,
    state: "pending-approval",
    arrange: (page) => openCashboxRoute(page, CASH_SESSION_PENDING),
    expected: (page) => page.getByRole("button", { name: "Aprobar cierre" }),
  },
  {
    route: CASH_SESSION_PENDING,
    state: "approve-dialog",
    opensDialog: true,
    arrange: async (page) => {
      await openCashboxRoute(page, CASH_SESSION_PENDING);
      await page.getByRole("button", { name: "Aprobar cierre" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Aprobar el cierre" }),
  },
  // The payments (payments-register cut): the list with rows, empty and with a read that fails; the register form, with cash and no open cashbox, with a
  // student chosen and their pending charges, and for a transfer; the detail of a declared transfer with its two decision dialogs, and of a confirmed payment.
  {
    route: PAYMENTS,
    state: "ready",
    arrange: (page) => openPaymentsRoute(page, PAYMENTS),
    expected: (page) => page.getByRole("table", { name: "Pagos registrados" }).getByRole("row").nth(1),
  },
  {
    route: PAYMENTS,
    state: "empty",
    preset: "payments-empty",
    arrange: (page) => openPaymentsRoute(page, PAYMENTS),
    expected: (page) => page.getByText("Todavía no hay pagos"),
  },
  {
    route: PAYMENTS,
    state: "load-error",
    preset: "payments-server-error",
    arrange: (page) => openPaymentsRoute(page, PAYMENTS),
    expected: (page) => page.getByText("No se pudieron cargar los pagos"),
  },
  {
    route: PAYMENTS_REGISTER,
    state: "no-session",
    arrange: (page) => openPaymentsRoute(page, PAYMENTS_REGISTER),
    expected: (page) => page.getByText("No tiene una caja abierta"),
  },
  {
    route: PAYMENTS_REGISTER,
    state: "student-chosen",
    preset: "cashbox-open",
    arrange: async (page) => {
      await openPaymentsRoute(page, PAYMENTS_REGISTER);
      await chooseStudent(page);
    },
    expected: (page) => page.getByRole("table", { name: /Cargos vigentes del estudiante/ }),
  },
  {
    route: PAYMENTS_REGISTER,
    state: "transfer",
    arrange: async (page) => {
      await openPaymentsRoute(page, PAYMENTS_REGISTER);
      await page.getByRole("combobox", { name: /Método de pago/ }).selectOption("BANK_TRANSFER");
    },
    expected: (page) => page.getByRole("textbox", { name: /Referencia bancaria/ }),
  },
  {
    route: PAYMENT_DECLARED,
    state: "declared",
    arrange: (page) => openPaymentsRoute(page, PAYMENT_DECLARED),
    expected: (page) => page.getByRole("button", { name: "Confirmar" }),
  },
  {
    route: PAYMENT_DECLARED,
    state: "confirm-dialog",
    opensDialog: true,
    arrange: async (page) => {
      await openPaymentsRoute(page, PAYMENT_DECLARED);
      await page.getByRole("button", { name: "Confirmar" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Confirmar la transferencia" }),
  },
  {
    route: PAYMENT_DECLARED,
    state: "reject-dialog",
    opensDialog: true,
    arrange: async (page) => {
      await openPaymentsRoute(page, PAYMENT_DECLARED);
      await page.getByRole("button", { name: "Rechazar" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Rechazar la transferencia" }),
  },
  {
    route: PAYMENT_CONFIRMED,
    state: "confirmed",
    arrange: (page) => openPaymentsRoute(page, PAYMENT_CONFIRMED),
    expected: (page) => page.getByRole("heading", { level: 3, name: "Datos del pago" }),
  },
  // The scholarships (scholarships-awards cut): the list with rows, empty and with a read that fails, and the form to create one; the awards with rows and empty;
  // the request of an award; the detail of a pending award with its reject dialog; and the simulator, with nothing asked and with its result.
  {
    route: SCHOLARSHIPS,
    state: "ready",
    arrange: (page) => openScholarshipsRoute(page, SCHOLARSHIPS),
    expected: (page) => page.getByRole("table", { name: "Becas de la institución" }).getByRole("row").nth(1),
  },
  {
    route: SCHOLARSHIPS,
    state: "empty",
    preset: "scholarships-empty",
    arrange: (page) => openScholarshipsRoute(page, SCHOLARSHIPS),
    expected: (page) => page.getByText("Todavía no hay becas"),
  },
  {
    route: SCHOLARSHIPS,
    state: "load-error",
    preset: "scholarships-server-error",
    arrange: (page) => openScholarshipsRoute(page, SCHOLARSHIPS),
    expected: (page) => page.getByText("No se pudieron cargar las becas"),
  },
  {
    route: SCHOLARSHIPS,
    state: "create-dialog",
    opensDialog: true,
    arrange: async (page) => {
      await openScholarshipsRoute(page, SCHOLARSHIPS);
      await page.getByRole("button", { name: "Crear beca" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Crear una beca" }),
  },
  {
    route: AWARDS,
    state: "ready",
    arrange: (page) => openScholarshipsRoute(page, AWARDS),
    expected: (page) => page.getByRole("table", { name: "Otorgamientos de becas" }).getByRole("row").nth(1),
  },
  {
    route: AWARDS,
    state: "empty",
    preset: "scholarships-empty",
    arrange: (page) => openScholarshipsRoute(page, AWARDS),
    expected: (page) => page.getByText("Todavía no hay otorgamientos"),
  },
  {
    route: AWARDS,
    state: "request-dialog",
    opensDialog: true,
    arrange: async (page) => {
      await openScholarshipsRoute(page, AWARDS);
      await page.getByRole("button", { name: "Solicitar otorgamiento" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Solicitar un otorgamiento" }),
  },
  {
    route: AWARD_PENDING,
    state: "pending",
    arrange: (page) => openScholarshipsRoute(page, AWARD_PENDING),
    expected: (page) => page.getByRole("button", { name: "Aprobar" }),
  },
  {
    route: AWARD_PENDING,
    state: "reject-dialog",
    opensDialog: true,
    arrange: async (page) => {
      await openScholarshipsRoute(page, AWARD_PENDING);
      await page.getByRole("button", { name: "Rechazar" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Rechazar el otorgamiento" }),
  },
  {
    route: SIMULATOR,
    state: "idle",
    arrange: (page) => openScholarshipsRoute(page, SIMULATOR),
    expected: (page) => page.getByText("Todavía no se ha simulado nada"),
  },
  {
    route: SIMULATOR,
    state: "result",
    arrange: async (page) => {
      await openScholarshipsRoute(page, SIMULATOR);
      await page.getByRole("combobox", { name: /^Estudiante/ }).fill("E-000001");
      await page.getByRole("option", { name: /E-000001/ }).click();
      await page.getByLabel(/^Plan de cobro/).selectOption({ label: "Primaria 2026" });
      await page.getByLabel(/^Período/).selectOption({ label: "2026-05, vence el 5 may 2026" });
      await page.getByRole("button", { name: "Simular" }).click();
    },
    expected: (page) => page.getByRole("table", { name: "Resultado de la simulación por concepto" }),
  },
  // The discounts (scholarships-discounts cut): the list with rows, empty and with a read that fails, the form to create one, and the form of one in use.
  {
    route: DISCOUNTS,
    state: "ready",
    arrange: (page) => openScholarshipsRoute(page, DISCOUNTS),
    expected: (page) => page.getByRole("table", { name: "Descuentos de la institución" }).getByRole("row").nth(1),
  },
  {
    route: DISCOUNTS,
    state: "empty",
    preset: "discounts-empty",
    arrange: (page) => openScholarshipsRoute(page, DISCOUNTS),
    expected: (page) => page.getByText("Todavía no hay descuentos"),
  },
  {
    route: DISCOUNTS,
    state: "load-error",
    preset: "discounts-server-error",
    arrange: (page) => openScholarshipsRoute(page, DISCOUNTS),
    expected: (page) => page.getByText("No se pudieron cargar los descuentos"),
  },
  {
    route: DISCOUNTS,
    state: "create-dialog",
    opensDialog: true,
    arrange: async (page) => {
      await openScholarshipsRoute(page, DISCOUNTS);
      await page.getByRole("button", { name: "Crear descuento" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Crear un descuento" }),
  },
  {
    route: DISCOUNTS,
    state: "edit-in-use-dialog",
    opensDialog: true,
    arrange: async (page) => {
      await openScholarshipsRoute(page, DISCOUNTS);
      await page.getByRole("button", { name: "Editar Convenio con la empresa" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Editar el descuento" }),
  },
  // The approval of a campaign discount (S7): the dialogs of the decision on what another person created, and a proposal that waits beside the terms in force.
  {
    route: DISCOUNTS,
    state: "approve-dialog",
    opensDialog: true,
    arrange: async (page) => {
      await openScholarshipsRoute(page, DISCOUNTS);
      await page.getByRole("button", { name: "Aprobar Campaña de verano" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Aprobar el descuento" }),
  },
  {
    route: DISCOUNTS,
    state: "reject-dialog",
    opensDialog: true,
    arrange: async (page) => {
      await openScholarshipsRoute(page, DISCOUNTS);
      await page.getByRole("button", { name: "Rechazar Campaña de verano" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Rechazar el descuento" }),
  },
  {
    route: DISCOUNTS,
    state: "proposal-pending",
    arrange: async (page) => {
      await openScholarshipsRoute(page, DISCOUNTS);
      await page.getByRole("button", { name: "Editar Campaña de matrícula" }).click();
      await page.getByRole("dialog", { name: "Editar el descuento" }).getByLabel(/^Porcentaje/).fill("25");
      await page.getByRole("dialog", { name: "Editar el descuento" }).getByRole("button", { name: "Guardar" }).click();
      // The page is analyzed once the dialog is gone, not while it fades out over the table.
      await page.getByRole("dialog").waitFor({ state: "detached" });
    },
    expected: (page) => page.getByRole("table", { name: "Descuentos de la institución" }).getByRole("row", { name: /Campaña de matrícula.*Propuesta pendiente/ }),
  },
  // The agreement assignments (scholarships-discounts cut): the list with rows and empty, the request, the detail of a pending one with its reject dialog.
  {
    route: ASSIGNMENTS,
    state: "ready",
    arrange: (page) => openScholarshipsRoute(page, ASSIGNMENTS),
    expected: (page) => page.getByRole("table", { name: "Asignaciones de descuentos de convenio" }).getByRole("row").nth(1),
  },
  {
    route: ASSIGNMENTS,
    state: "empty",
    preset: "discounts-empty",
    arrange: (page) => openScholarshipsRoute(page, ASSIGNMENTS),
    expected: (page) => page.getByText("Todavía no hay asignaciones"),
  },
  {
    route: ASSIGNMENTS,
    state: "request-dialog",
    opensDialog: true,
    arrange: async (page) => {
      await openScholarshipsRoute(page, ASSIGNMENTS);
      await page.getByRole("button", { name: "Solicitar asignación" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Solicitar una asignación" }),
  },
  {
    route: ASSIGNMENT_PENDING,
    state: "pending",
    arrange: (page) => openScholarshipsRoute(page, ASSIGNMENT_PENDING),
    expected: (page) => page.getByRole("button", { name: "Aprobar" }),
  },
  {
    route: ASSIGNMENT_PENDING,
    state: "reject-dialog",
    opensDialog: true,
    arrange: async (page) => {
      await openScholarshipsRoute(page, ASSIGNMENT_PENDING);
      await page.getByRole("button", { name: "Rechazar" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Rechazar la asignación" }),
  },
];
