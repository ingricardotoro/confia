import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import {
  problem,
  taxRate,
  taxRateDetail,
  taxRateDetailRecord,
  taxRatePage,
  taxRatePeriod,
  taxRatePeriodRecord,
  taxRateRecord,
} from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderCatalog } from "../../test/render-catalog.tsx";

// The detail of a tax rate over the real route, the real query layer and the real transport: the history of validities,
// the new validity with its exact body, the refusals (overlap, inactive rate, stale version, a day outside the window),
// the activation and the deactivation with the version, and the states of the load. The percentages are fictitious.
installMswServer();

const RATES = "/api/v1/catalog/tax-rates";
const TIMEOUT = { timeout: 10_000 };
const RATE_ID = "00000000-0000-4000-8000-0000000000a1";

const FIRST = taxRatePeriod({ id: "00000000-0000-4000-8000-0000000000f1", taxRateId: RATE_ID, ratePercent: "7.5000", validFrom: "2027-01-01", validTo: "2027-06-30" });
const SECOND = taxRatePeriod({ id: "00000000-0000-4000-8000-0000000000f2", taxRateId: RATE_ID, ratePercent: "12.3400", validFrom: "2027-07-01", validTo: null });
const RATE = taxRateDetail({ id: RATE_ID, code: "IMP-A", name: "Impuesto de prueba A", version: 3, periods: [FIRST, SECOND] });

const gets = () => requests().filter((sent) => sent.method === "GET" && sent.path === `${RATES}/${RATE_ID}`);
const posts = () => requests().filter((sent) => sent.method === "POST");
const puts = () => requests().filter((sent) => sent.method === "PUT");
const bodyOf = (sent: { body: string } | undefined): unknown => JSON.parse(sent?.body ?? "{}");

async function openDetail(detail: unknown = RATE) {
  route("get", `${RATES}/${RATE_ID}`, taxRateDetailRecord(detail));
  const rendered = renderCatalog(`/catalog/tax-rates/${RATE_ID}`);
  await screen.findByRole("heading", { level: 2, name: (detail as { name: string }).name }, TIMEOUT);
  return rendered;
}

async function openAddDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole("button", { name: "Nueva vigencia" }));
  return screen.findByRole("dialog", { name: "Nueva vigencia" });
}

async function fillPeriod(dialog: HTMLElement, user: ReturnType<typeof userEvent.setup>, values: { rate?: string; from?: string; to?: string } = {}) {
  await user.type(within(dialog).getByLabelText(/Porcentaje/), values.rate ?? "9.25");
  await user.type(within(dialog).getByLabelText(/Vigente desde/), values.from ?? "2027-09-01");
  if (values.to !== undefined) await user.type(within(dialog).getByLabelText(/Vigente hasta/), values.to);
}

describe("the detail of a tax rate", () => {
  it("shows the data of the rate and every validity, closed and open, newest first, with each percentage as the server sent it", async () => {
    await openDetail();

    expect(screen.getByRole("link", { name: "Tasas de impuesto" })).toHaveAttribute("aria-current", "page");
    const table = screen.getByRole("table", { name: "Vigencias de Impuesto de prueba A" });
    const [, newest, oldest] = within(table).getAllByRole("row");
    expect(within(newest as HTMLElement).getByText("12.3400 %")).toBeInTheDocument();
    expect(within(newest as HTMLElement).getByText("Abierta")).toBeInTheDocument();
    expect(within(oldest as HTMLElement).getByText("7.5000 %")).toBeInTheDocument();
    expect(within(oldest as HTMLElement).getByText("Cerrada")).toBeInTheDocument();
    expect(within(oldest as HTMLElement).getByText("30 jun 2027")).toBeInTheDocument();
  });

  it("shows the empty state of a rate with no validity, with the way forward", async () => {
    await openDetail(taxRateDetail({ ...RATE, periods: [] }));

    expect(screen.getByText("Esta tasa todavía no tiene vigencias")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Registrar la primera vigencia" })).toBeInTheDocument();
  });

  it("shows the error state of a rate that fails, with the trace id, and loads it on retry", async () => {
    const user = userEvent.setup();
    route("get", `${RATES}/${RATE_ID}`, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }), taxRateDetailRecord(RATE));
    renderCatalog(`/catalog/tax-rates/${RATE_ID}`);

    expect(await screen.findByText("No se pudo cargar la tasa de impuesto", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(/01J8Z4K2QF9P0X/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(await screen.findByRole("heading", { level: 2, name: "Impuesto de prueba A" })).toBeInTheDocument();
  });

  it("tells a rate that does not exist, or that is another institution's, as not found", async () => {
    route("get", `${RATES}/${RATE_ID}`, problem("resource-not-found", 404));
    renderCatalog(`/catalog/tax-rates/${RATE_ID}`);

    expect(await screen.findByText("No se pudo cargar la tasa de impuesto", undefined, TIMEOUT)).toBeInTheDocument();
    expect(screen.getByText(admin.problem.notFound)).toBeInTheDocument();
  });

  it("goes back to the rates", async () => {
    const user = userEvent.setup();
    const { router } = await openDetail();
    route("get", RATES, taxRatePage([taxRate()]));

    await user.click(screen.getByRole("link", { name: "Volver a las tasas de impuesto" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/catalog/tax-rates");
    });
  });
});

describe("a new validity", () => {
  it("sends the percentage as the text typed, without an Idempotency-Key, and shows the new history from the server", async () => {
    const user = userEvent.setup();
    await openDetail();
    const third = taxRatePeriod({ id: "00000000-0000-4000-8000-0000000000f3", taxRateId: RATE_ID, ratePercent: "9.2500", validFrom: "2027-09-01", validTo: null });
    route("post", `${RATES}/${RATE_ID}/periods`, taxRatePeriodRecord(third));
    route("get", `${RATES}/${RATE_ID}`, taxRateDetailRecord({ ...RATE, periods: [FIRST, { ...SECOND, validTo: "2027-08-31" }, third] }));
    const dialog = await openAddDialog(user);
    const before = gets().length;

    await fillPeriod(dialog, user);
    await user.click(within(dialog).getByRole("button", { name: "Guardar vigencia" }));

    expect(await screen.findByText("Se registró la vigencia de «Impuesto de prueba A» desde el 1 sept 2027.")).toBeInTheDocument();
    expect(bodyOf(posts()[0])).toEqual({ ratePercent: "9.25", validFrom: "2027-09-01" });
    expect(posts()[0]?.headers.has("Idempotency-Key")).toBe(false);
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(before);
    });
    const table = await screen.findByRole("table", { name: "Vigencias de Impuesto de prueba A" });
    expect(await within(table).findByText("9.2500 %")).toBeInTheDocument();
    expect(within(table).getAllByText("Cerrada")).toHaveLength(2);
    expect(within(table).getAllByText("Abierta")).toHaveLength(1);
  });

  it("sends the end date when there is one", async () => {
    const user = userEvent.setup();
    await openDetail();
    route("post", `${RATES}/${RATE_ID}/periods`, taxRatePeriodRecord(taxRatePeriod({ taxRateId: RATE_ID, validFrom: "2027-09-01", validTo: "2027-12-31" })));
    const dialog = await openAddDialog(user);

    await fillPeriod(dialog, user, { rate: "100", to: "2027-12-31" });
    await user.click(within(dialog).getByRole("button", { name: "Guardar vigencia" }));

    await screen.findByText(/Se registró la vigencia/);
    expect(bodyOf(posts()[0])).toEqual({ ratePercent: "100", validFrom: "2027-09-01", validTo: "2027-12-31" });
  });

  it("refuses an empty percentage and start before sending", async () => {
    const user = userEvent.setup();
    await openDetail();
    const dialog = await openAddDialog(user);

    await user.click(within(dialog).getByRole("button", { name: "Guardar vigencia" }));

    expect(await within(dialog).findByText("Hay 2 campos con errores.")).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it.each([
    ["0", /mayor que cero y no pasar de 100/],
    ["-3", /mayor que cero y no pasar de 100/],
    ["100.0001", /mayor que cero y no pasar de 100/],
    ["101", /mayor que cero y no pasar de 100/],
    ["12.34567", /hasta cuatro decimales/],
    ["12,5", /hasta cuatro decimales/],
    ["abc", /hasta cuatro decimales/],
  ])("refuses the percentage %s before sending, with its own message", async (rate, message) => {
    const user = userEvent.setup();
    await openDetail();
    const dialog = await openAddDialog(user);

    await fillPeriod(dialog, user, { rate });
    await user.click(within(dialog).getByRole("button", { name: "Guardar vigencia" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Porcentaje/)).toHaveAccessibleDescription(message);
    });
    expect(posts()).toHaveLength(0);
  });

  it("refuses a day outside 2000 to 2100 and an end before the start before sending, with their own messages", async () => {
    const user = userEvent.setup();
    await openDetail();
    const dialog = await openAddDialog(user);

    await fillPeriod(dialog, user, { from: "2101-01-01" });
    await user.click(within(dialog).getByRole("button", { name: "Guardar vigencia" }));
    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Vigente desde/)).toHaveAccessibleDescription(/entre el 1 de enero de 2000 y el 31 de diciembre de 2100/);
    });
    fireEvent.change(within(dialog).getByLabelText(/Vigente desde/), { target: { value: "2027-09-01" } });
    fireEvent.change(within(dialog).getByLabelText(/Vigente hasta/), { target: { value: "2027-08-01" } });
    await user.click(within(dialog).getByRole("button", { name: "Guardar vigencia" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Vigente hasta/)).toHaveAccessibleDescription(/no puede ser anterior a la de inicio/);
    });
    expect(posts()).toHaveLength(0);
  });

  it("tells an overlap in its own words, keeps what was typed, and reads the rate again", async () => {
    const user = userEvent.setup();
    await openDetail();
    route("post", `${RATES}/${RATE_ID}/periods`, problem("tax-rate-period-overlap", 409));
    route("get", `${RATES}/${RATE_ID}`, taxRateDetailRecord({ ...RATE, version: 4 }));
    const dialog = await openAddDialog(user);
    const before = gets().length;

    await fillPeriod(dialog, user, { from: "2027-08-01", to: "2027-08-31" });
    await user.click(within(dialog).getByRole("button", { name: "Guardar vigencia" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(admin.problem.taxRatePeriodOverlap);
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(before);
    });
    expect(within(dialog).getByLabelText(/Porcentaje/)).toHaveValue("9.25");
    expect(posts()).toHaveLength(1);
  });

  it("puts the field errors of a 400 under the percentage and the dates", async () => {
    const user = userEvent.setup();
    await openDetail();
    route(
      "post",
      `${RATES}/${RATE_ID}/periods`,
      problem("validation-failed", 400, { errors: [{ field: "ratePercent", reason: "pattern" }, { field: "validFrom", reason: "validity-day" }] }),
    );
    const dialog = await openAddDialog(user);

    await fillPeriod(dialog, user);
    await user.click(within(dialog).getByRole("button", { name: "Guardar vigencia" }));

    await waitFor(() => {
      expect(within(dialog).getByLabelText(/Porcentaje/)).toHaveAccessibleDescription(/hasta cuatro decimales/);
    });
    expect(within(dialog).getByLabelText(/Vigente desde/)).toHaveAccessibleDescription(/entre el 1 de enero de 2000/);
  });

  it("tells a rate that was deactivated by someone else (inactive reference) in the form", async () => {
    const user = userEvent.setup();
    await openDetail();
    route("post", `${RATES}/${RATE_ID}/periods`, problem("inactive-reference", 409));
    const dialog = await openAddDialog(user);

    await fillPeriod(dialog, user);
    await user.click(within(dialog).getByRole("button", { name: "Guardar vigencia" }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(admin.problem.inactiveReference);
  });

  it("closes the dialog without sending anything on cancel", async () => {
    const user = userEvent.setup();
    await openDetail();
    const dialog = await openAddDialog(user);

    await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(posts()).toHaveLength(0);
  });
});

describe("activating and deactivating a tax rate", () => {
  it("deactivates with the version the rate was read at, tells it, and then takes no new validity", async () => {
    const user = userEvent.setup();
    await openDetail();
    route("put", `${RATES}/${RATE_ID}`, taxRateRecord(taxRate({ id: RATE_ID, name: "Impuesto de prueba A", isActive: false, version: 4 })));
    route("get", `${RATES}/${RATE_ID}`, taxRateDetailRecord({ ...RATE, isActive: false, version: 4 }));

    await user.click(screen.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" }));

    expect(await screen.findByText("Se desactivó la tasa «Impuesto de prueba A».")).toBeInTheDocument();
    expect(bodyOf(puts()[0])).toEqual({ version: 3, name: "Impuesto de prueba A", isActive: false });
    expect(puts()[0]?.headers.has("Idempotency-Key")).toBe(false);
    expect(await screen.findByRole("button", { name: "Activar tasa Impuesto de prueba A" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Nueva vigencia" })).toBeDisabled();
    expect(screen.getByText(/está inactiva y no admite vigencias nuevas/)).toBeInTheDocument();
  });

  it("activates an inactive rate with its version", async () => {
    const user = userEvent.setup();
    await openDetail(taxRateDetail({ ...RATE, isActive: false, version: 6 }));
    route("put", `${RATES}/${RATE_ID}`, taxRateRecord(taxRate({ id: RATE_ID, name: "Impuesto de prueba A", version: 7 })));
    route("get", `${RATES}/${RATE_ID}`, taxRateDetailRecord({ ...RATE, version: 7 }));

    await user.click(screen.getByRole("button", { name: "Activar tasa Impuesto de prueba A" }));

    expect(await screen.findByText("Se activó la tasa «Impuesto de prueba A».")).toBeInTheDocument();
    expect(bodyOf(puts()[0])).toEqual({ version: 6, name: "Impuesto de prueba A", isActive: true });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Nueva vigencia" })).toBeEnabled();
    });
  });

  it("tells the rate changed, reads it again, and sends the new version on the next try, when the version was stale", async () => {
    const user = userEvent.setup();
    await openDetail();
    route("put", `${RATES}/${RATE_ID}`, problem("stale-version", 409), taxRateRecord(taxRate({ id: RATE_ID, isActive: false, version: 8 })));
    route("get", `${RATES}/${RATE_ID}`, taxRateDetailRecord({ ...RATE, version: 7 }));
    const before = gets().length;

    await user.click(screen.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" }));

    expect(await screen.findByText(admin.problem.staleVersion)).toBeInTheDocument();
    await waitFor(() => {
      expect(gets().length).toBeGreaterThan(before);
    });
    await user.click(screen.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" }));
    await waitFor(() => {
      expect(puts()).toHaveLength(2);
    });
    expect(bodyOf(puts()[0])).toMatchObject({ version: 3 });
    expect(bodyOf(puts()[1])).toMatchObject({ version: 7 });
  });

  it("tells a refusal that is not about the version, with the trace id, and changes nothing on screen", async () => {
    const user = userEvent.setup();
    await openDetail();
    route("put", `${RATES}/${RATE_ID}`, problem("forbidden", 403, { traceId: "01J8Z4K2QF9P0X" }));

    await user.click(screen.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" }));

    expect(await screen.findByText(admin.problem.forbidden)).toBeInTheDocument();
    expect(screen.getByText("Identificador para soporte: 01J8Z4K2QF9P0X")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Nueva vigencia" })).toBeEnabled();
  });

  it("sends one request even if the button is pressed twice before the answer", async () => {
    await openDetail();
    route("put", `${RATES}/${RATE_ID}`, taxRateRecord(taxRate({ id: RATE_ID, name: "Impuesto de prueba A", isActive: false, version: 4 })));
    const button = screen.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" });

    fireEvent.click(button);
    fireEvent.click(button);

    await screen.findByText("Se desactivó la tasa «Impuesto de prueba A».");
    expect(puts()).toHaveLength(1);
  });
});
