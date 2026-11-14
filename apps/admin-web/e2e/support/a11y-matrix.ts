import { randomUUID } from "node:crypto";
import type { Page, Route } from "@playwright/test";
import { problem } from "../../src/test/contract-responses.ts";
import type { CombinationId, GateCombination } from "./a11y-report.ts";
import { MOCK_API_URL } from "./scenario-header.ts";

// The states of the screens that exist in this part (build-integrity, matrix of the gate): the boot, the
// signed in page, the route that is not found and, since the sign in screen, its eight states. The gate evaluates each one in both themes. The states
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
const IDENTIFIER = "docente.prueba@example.test";

/** Types fictitious credentials and sends the form; the password is made at run time, so none is written here. */
async function submitCredentials(page: Page): Promise<void> {
  await page.goto(SIGN_IN);
  await page.locator("#sign-in-identifier").fill(IDENTIFIER);
  await page.locator("#sign-in-password").fill(randomUUID());
  await page.getByRole("button", { name: "Iniciar sesión" }).click();
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

const ENROLLMENTS = "/enrollments";

/** The enrollments list with its rows on the screen: the surname of the first row of the seed proves it. */
async function openEnrollmentsRoute(page: Page, path: string = ENROLLMENTS): Promise<void> {
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

/** The six combinations of the minimum, written apart from the matrix so a dropped one is noticed (BX74). */
export const MINIMUM: readonly CombinationId[] = [
  { route: "/", state: "boot-restoring" },
  { route: "/", state: "boot-failed" },
  { route: "/", state: "signed-in-loading" },
  { route: "/", state: "signed-in-ready" },
  { route: "/", state: "signed-in-error" },
  { route: "/no-existe", state: "not-found" },
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
    route: ENROLLMENTS,
    state: "ready",
    arrange: (page) => openEnrollmentsRoute(page),
    expected: (page) => page.getByRole("cell", { name: "Aguilar, Ana", exact: true }).first(),
  },
  {
    route: ENROLLMENTS,
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
    route: ENROLLMENTS,
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
    route: ENROLLMENTS,
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
    route: ENROLLMENTS,
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
    route: ENROLLMENTS,
    state: "dialog-detail",
    opensDialog: true,
    arrange: async (page) => {
      // Mateo Lagos is in "Primero A" (the seeded section 00...4001), with a section move in his history.
      await openEnrollmentsRoute(page, `${ENROLLMENTS}?sectionId=00000000-0000-4000-8000-000000004001`);
      await page.getByRole("button", { name: "Ver detalle de la matrícula de Mateo Lagos" }).click();
    },
    expected: (page) => page.getByRole("dialog", { name: "Detalle de la matrícula" }).getByRole("table"),
  },
  {
    route: ENROLLMENTS,
    state: "empty",
    preset: "enrollments-empty",
    arrange: (page) => openEnrollmentsRoute(page),
    expected: (page) => page.getByText("Todavía no hay matrículas"),
  },
  {
    route: ENROLLMENTS,
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
];
