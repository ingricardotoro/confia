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
];
