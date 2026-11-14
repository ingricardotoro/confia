import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import { guardian, guardianPage, problem, student, studentGuardians, studentLink, studentPage } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderFeature } from "../../test/render-feature.tsx";
import { studentsFeature } from "../students/feature.tsx";

// The dialog that manages the whole set of guardians of a student, opened from the row of the students screen, over the real
// routes, query layer and transport. The browser never adds percentages: these tests prove it shows the sum the server gave,
// sends the typed text as numbers, and tells each reason the server refuses a split.
installMswServer();

const STUDENTS = "/api/v1/students";
const GUARDIANS = "/api/v1/guardians";
const LINKS = `${STUDENTS}/:id/guardians`;
const TIMEOUT = { timeout: 10_000 };

const ANA = student({ id: "00000000-0000-4000-8000-000000005001", firstName: "Ana", lastName: "Pérez", studentCode: "E-000001" });
const ROSA = studentLink({ guardianId: "00000000-0000-4000-8000-000000009001", guardianCode: "P-000001", guardianFirstName: "Rosa", guardianLastName: "Mejía", relationship: "MOTHER", financialResponsibilityPercent: 60, isPrimaryPayer: true, isBillingRecipient: true });
const LUIS = studentLink({ guardianId: "00000000-0000-4000-8000-000000009002", guardianCode: "P-000002", guardianFirstName: "Luis", guardianLastName: "Paz", relationship: "FATHER", financialResponsibilityPercent: 40, isPrimaryPayer: false, isBillingRecipient: false });

const putLinks = () => requests().filter((sent) => sent.method === "PUT" && sent.path.endsWith("/guardians") && sent.path.startsWith(STUDENTS));
const bodyOf = (sent: { body: string } | undefined): unknown => JSON.parse(sent?.body ?? "{}");

afterEach(() => {
  vi.restoreAllMocks();
});

async function openDialog(links: ReturnType<typeof studentLink>[], totalPercent?: number) {
  route("get", STUDENTS, studentPage([ANA]));
  route("get", LINKS, studentGuardians(links, totalPercent === undefined ? {} : { totalPercent }));
  const app = renderFeature(studentsFeature, "/students");
  await screen.findByRole("row", { name: /Pérez/ }, TIMEOUT);
  const user = userEvent.setup();
  const opener = screen.getByRole("button", { name: "Encargados de Ana Pérez" });
  await user.click(opener);
  const dialog = await screen.findByRole("dialog", { name: "Encargados de Ana Pérez" });
  await within(dialog).findByText(/Suma guardada actualmente/, undefined, TIMEOUT);
  return { ...app, user, dialog, opener };
}

const save = (dialog: HTMLElement) => within(dialog).getByRole("button", { name: "Guardar encargados" });

describe("the current set", () => {
  it("shows a skeleton while it loads, then each guardian with relationship, percentage and marks, and the sum the server gave", async () => {
    route("get", STUDENTS, studentPage([ANA]));
    route("get", LINKS, studentGuardians([ROSA, LUIS]));
    renderFeature(studentsFeature, "/students");
    await screen.findByRole("row", { name: /Pérez/ }, TIMEOUT);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Encargados de Ana Pérez" }));
    const dialog = await screen.findByRole("dialog", { name: "Encargados de Ana Pérez" });

    expect(await within(dialog).findByText("Suma guardada actualmente (la calcula el servidor): 100.00 %", undefined, TIMEOUT)).toBeInTheDocument();
    const rosa = within(dialog).getByRole("group", { name: "Rosa Mejía · P-000001" });
    expect(within(rosa).getByLabelText(/Parentesco/)).toHaveValue("MOTHER");
    expect(within(rosa).getByLabelText(/Porcentaje/)).toHaveValue("60.00");
    expect(within(rosa).getByRole("checkbox", { name: "Pagador principal" })).toBeChecked();
    expect(within(rosa).getByRole("checkbox", { name: "Destinatario de la factura" })).toBeChecked();
    const luis = within(dialog).getByRole("group", { name: "Luis Paz · P-000002" });
    expect(within(luis).getByLabelText(/Porcentaje/)).toHaveValue("40.00");
    expect(within(luis).getByRole("checkbox", { name: "Pagador principal" })).not.toBeChecked();
  });

  it("shows the sum exactly as the server says it, not one of its own", async () => {
    const { dialog } = await openDialog([ROSA, LUIS], 99.99);

    expect(within(dialog).getByText(/: 99\.99 %/)).toBeInTheDocument();
  });

  it("says how to start when the student has no guardians, and states the rule", async () => {
    const { dialog } = await openDialog([]);

    expect(within(dialog).getByText("Este estudiante no tiene encargados. Agregue uno con el buscador.")).toBeInTheDocument();
    expect(within(dialog).getByText(admin.guardians.links.rule)).toBeInTheDocument();
    expect(within(dialog).queryByText(admin.guardians.links.removeAllWarning)).not.toBeInTheDocument();
  });

  it("offers a retry when the set cannot be read, with the trace id", async () => {
    route("get", STUDENTS, studentPage([ANA]));
    route("get", LINKS, problem("internal-error", 500, { traceId: "t-links" }), studentGuardians([ROSA]));
    renderFeature(studentsFeature, "/students");
    await screen.findByRole("row", { name: /Pérez/ }, TIMEOUT);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Encargados de Ana Pérez" }));
    const dialog = await screen.findByRole("dialog", { name: "Encargados de Ana Pérez" });
    expect(await within(dialog).findByText("No se pudieron cargar los encargados del estudiante", undefined, TIMEOUT)).toBeInTheDocument();
    expect(dialog).toHaveTextContent("t-links");
    expect(within(dialog).queryByRole("button", { name: "Guardar encargados" })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Reintentar" }));

    expect(await within(dialog).findByRole("group", { name: "Rosa Mejía · P-000001" })).toBeInTheDocument();
  });

  it("gives the focus back to the row button when it closes", async () => {
    const { user, dialog, opener } = await openDialog([ROSA]);

    await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(opener).toHaveFocus();
    expect(putLinks()).toHaveLength(0);
  });
});

describe("adding a guardian", () => {
  it("searches active guardians only from two characters, after a pause, and leaves out the ones already in the set", async () => {
    const { user, dialog } = await openDialog([ROSA]);
    route("get", GUARDIANS, guardianPage([guardian({ id: ROSA.guardianId, firstName: "Rosa", lastName: "Mejía" }), guardian({ id: "00000000-0000-4000-8000-000000009002", firstName: "Luis", lastName: "Paz", guardianCode: "P-000002" })]));
    const box = within(dialog).getByRole("combobox", { name: /Agregar un encargado/ });

    await user.type(box, "m");
    expect(within(dialog).getByText("Escriba al menos 2 caracteres para buscar.", { selector: "p" })).toBeInTheDocument();
    expect(requests().filter((sent) => sent.path === GUARDIANS)).toHaveLength(0);
    await user.type(box, "e");

    expect(await within(dialog).findByRole("option", { name: "Luis Paz · P-000002" }, TIMEOUT)).toBeInTheDocument();
    expect(within(dialog).queryByRole("option", { name: /Rosa/ })).not.toBeInTheDocument();
    const query = new URLSearchParams(requests().filter((sent) => sent.path === GUARDIANS).at(-1)?.search);
    expect(Object.fromEntries(query)).toEqual({ page: "0", size: "10", sort: "lastName,asc", status: "ACTIVE", q: "me" });
  });

  it("adds with the keyboard (arrows and Enter), moves the focus to the new row, announces it, and does not send the set", async () => {
    const { user, dialog } = await openDialog([ROSA]);
    route("get", GUARDIANS, guardianPage([guardian({ id: "00000000-0000-4000-8000-000000009002", firstName: "Luis", lastName: "Paz", guardianCode: "P-000002" })]));
    await user.type(within(dialog).getByRole("combobox", { name: /Agregar un encargado/ }), "lu");
    await within(dialog).findByRole("option", { name: "Luis Paz · P-000002" }, TIMEOUT);

    await user.keyboard("{ArrowDown}{Enter}");

    const luis = await within(dialog).findByRole("group", { name: "Luis Paz · P-000002" });
    expect(within(luis).getByLabelText(/Parentesco/)).toHaveFocus();
    expect(within(luis).getByLabelText(/Porcentaje/)).toHaveValue("");
    expect(within(dialog).getByText("Se agregó a Luis Paz. Complete el parentesco y el porcentaje.", { selector: "p[aria-live]" })).toBeInTheDocument();
    expect(within(dialog).getByRole("combobox", { name: /Agregar un encargado/ })).toHaveValue("");
    expect(putLinks()).toHaveLength(0);
  });

  it("does not send the set when Enter is pressed in the search box with nothing highlighted", async () => {
    const { user, dialog } = await openDialog([ROSA]);

    await user.type(within(dialog).getByRole("combobox", { name: /Agregar un encargado/ }), "zz{Enter}");

    expect(putLinks()).toHaveLength(0);
  });

  it("tells when the search finds nobody, and offers a retry when it fails", async () => {
    const { user, dialog } = await openDialog([]);
    route("get", GUARDIANS, guardianPage([]));
    await user.type(within(dialog).getByRole("combobox", { name: /Agregar un encargado/ }), "zz");
    expect(await within(dialog).findByText(/No se encontró ningún encargado activo/, undefined, TIMEOUT)).toBeInTheDocument();

    route("get", GUARDIANS, problem("internal-error", 500), guardianPage([guardian({ firstName: "Eva", lastName: "Rivas" })]));
    await user.type(within(dialog).getByRole("combobox", { name: /Agregar un encargado/ }), "x");
    expect(await within(dialog).findByText("No se pudo buscar encargados.", undefined, TIMEOUT)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Reintentar búsqueda" }));

    expect(await within(dialog).findByRole("option", { name: /Eva Rivas/ }, TIMEOUT)).toBeInTheDocument();
  });
});

describe("saving the set", () => {
  it("replaces the set with exactly the members of the contract, the percentages as numbers, and no Idempotency-Key", async () => {
    const { user, dialog } = await openDialog([ROSA, LUIS]);
    route("put", LINKS, studentGuardians([{ ...ROSA, financialResponsibilityPercent: 50 }, { ...LUIS, financialResponsibilityPercent: 50 }]));
    await user.clear(within(dialog).getAllByLabelText(/Porcentaje/)[0] as HTMLElement);
    await user.type(within(dialog).getAllByLabelText(/Porcentaje/)[0] as HTMLElement, "50");
    await user.clear(within(dialog).getAllByLabelText(/Porcentaje/)[1] as HTMLElement);
    await user.type(within(dialog).getAllByLabelText(/Porcentaje/)[1] as HTMLElement, "50.00");

    await user.click(save(dialog));

    expect(await screen.findByText("Se guardaron los encargados de «Ana Pérez». Suma: 100.00 %.")).toBeInTheDocument();
    expect(bodyOf(putLinks()[0])).toEqual({
      links: [
        { guardianId: ROSA.guardianId, relationship: "MOTHER", financialResponsibilityPercent: 50, isPrimaryPayer: true, isBillingRecipient: true },
        { guardianId: LUIS.guardianId, relationship: "FATHER", financialResponsibilityPercent: 50, isPrimaryPayer: false, isBillingRecipient: false },
      ],
    });
    expect(putLinks()[0]?.headers.has("Idempotency-Key")).toBe(false);
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
  });

  it("sends three guardians at 33.33, 33.33 and 33.34 without changing a digit", async () => {
    const { user, dialog } = await openDialog([ROSA, LUIS]);
    route("get", GUARDIANS, guardianPage([guardian({ id: "00000000-0000-4000-8000-000000009003", firstName: "Eva", lastName: "Rivas", guardianCode: "P-000003" })]));
    route("put", LINKS, studentGuardians([ROSA, LUIS]));
    await user.type(within(dialog).getByRole("combobox", { name: /Agregar un encargado/ }), "ev");
    await user.click(await within(dialog).findByRole("option", { name: /Eva Rivas/ }, TIMEOUT));
    const eva = within(dialog).getByRole("group", { name: "Eva Rivas · P-000003" });
    await user.selectOptions(within(eva).getByLabelText(/Parentesco/), "OTHER");
    const percents = within(dialog).getAllByLabelText(/Porcentaje/);
    for (const [index, value] of ["33.33", "33.33", "33.34"].entries()) {
      await user.clear(percents[index] as HTMLElement);
      await user.type(percents[index] as HTMLElement, value);
    }

    await user.click(save(dialog));

    await screen.findByText(/Se guardaron los encargados/);
    const sent = bodyOf(putLinks()[0]) as { links: { financialResponsibilityPercent: number }[] };
    expect(sent.links.map((link) => link.financialResponsibilityPercent)).toEqual([33.33, 33.33, 33.34]);
    expect(putLinks()[0]?.body).toContain('"financialResponsibilityPercent":33.34');
  });

  it("removes a guardian from the set, announces it, moves the focus to the search box and sends the rest", async () => {
    const { user, dialog } = await openDialog([ROSA, LUIS]);
    route("put", LINKS, studentGuardians([{ ...ROSA, financialResponsibilityPercent: 100 }]));

    await user.click(within(dialog).getByRole("button", { name: "Quitar a Luis Paz del reparto" }));

    expect(within(dialog).queryByRole("group", { name: /Luis Paz/ })).not.toBeInTheDocument();
    expect(within(dialog).getByText("Se quitó a Luis Paz del reparto. Falta guardar.", { selector: "p[aria-live]" })).toBeInTheDocument();
    expect(within(dialog).getByRole("combobox", { name: /Agregar un encargado/ })).toHaveFocus();
    await user.clear(within(dialog).getByLabelText(/Porcentaje/));
    await user.type(within(dialog).getByLabelText(/Porcentaje/), "100");
    await user.click(save(dialog));
    await screen.findByText(/Se guardaron los encargados/);
    expect((bodyOf(putLinks()[0]) as { links: unknown[] }).links).toHaveLength(1);
  });

  it("warns that saving an empty set removes every link, sends an empty list, and says so", async () => {
    const { user, dialog } = await openDialog([ROSA]);
    route("put", LINKS, studentGuardians([]));

    await user.click(within(dialog).getByRole("button", { name: "Quitar a Rosa Mejía del reparto" }));
    expect(within(dialog).getByText(admin.guardians.links.removeAllWarning)).toBeInTheDocument();
    await user.click(save(dialog));

    expect(await screen.findByText("Se quitaron todos los encargados de «Ana Pérez».")).toBeInTheDocument();
    expect(bodyOf(putLinks()[0])).toEqual({ links: [] });
  });

  it("warns on a row whose guardian became inactive", async () => {
    const { dialog } = await openDialog([{ ...ROSA, guardianStatus: "INACTIVE" }]);

    expect(within(dialog).getByText(admin.guardians.links.inactiveGuardian)).toBeInTheDocument();
  });

  it("sends once while the request is in flight", async () => {
    const { user, dialog } = await openDialog([ROSA]);
    route("put", LINKS, studentGuardians([ROSA]));

    await user.dblClick(save(dialog));

    await screen.findByText(/Se guardaron los encargados/);
    expect(putLinks()).toHaveLength(1);
  });
});

describe("what is refused before sending", () => {
  it("asks for the relationship and the percentage of a new row, in the summary and under the fields, with the focus on the summary", async () => {
    const { user, dialog } = await openDialog([]);
    route("get", GUARDIANS, guardianPage([guardian({ id: "00000000-0000-4000-8000-000000009003", firstName: "Eva", lastName: "Rivas", guardianCode: "P-000003" })]));
    await user.type(within(dialog).getByRole("combobox", { name: /Agregar un encargado/ }), "ev");
    await user.click(await within(dialog).findByRole("option", { name: /Eva Rivas/ }, TIMEOUT));

    await user.click(save(dialog));

    const summary = await within(dialog).findByText("Hay 2 campos con errores.");
    expect(summary.closest("[tabindex]")).toHaveFocus();
    const eva = within(dialog).getByRole("group", { name: "Eva Rivas · P-000003" });
    expect(within(eva).getByLabelText(/Parentesco/)).toHaveAccessibleDescription(admin.field.required);
    expect(within(eva).getByLabelText(/Porcentaje/)).toHaveAccessibleDescription(expect.stringContaining(admin.field.required));
    expect(putLinks()).toHaveLength(0);
  });

  it.each([
    ["101", admin.field.percentRange],
    ["0", admin.field.percentRange],
    ["33.333", admin.field.percentFormat],
    ["abc", admin.field.percentFormat],
  ])("refuses the percentage %j with its own message and sends nothing", async (typed, message) => {
    const { user, dialog } = await openDialog([ROSA]);
    await user.clear(within(dialog).getByLabelText(/Porcentaje/));
    await user.type(within(dialog).getByLabelText(/Porcentaje/), typed);

    await user.click(save(dialog));

    expect(await within(dialog).findByLabelText(/Porcentaje/)).toHaveAccessibleDescription(expect.stringContaining(message));
    expect(putLinks()).toHaveLength(0);
  });
});

describe("what the server refuses", () => {
  it.each([
    ["sum-not-100", admin.problem.splitSumNot100],
    ["multiple-primary-payer", admin.problem.splitMultiplePrimaryPayer],
    ["multiple-billing-recipient", admin.problem.splitMultipleBillingRecipient],
    ["duplicate-guardian", admin.problem.splitDuplicateGuardian],
  ])("tells the split reason %s in an alert with the focus, and keeps what was typed", async (reason, message) => {
    const { user, dialog } = await openDialog([ROSA, LUIS]);
    route("put", LINKS, problem("invalid-responsibility-split", 400, { errors: [{ field: "links", reason }], traceId: "t-split" }));
    await user.clear(within(dialog).getAllByLabelText(/Porcentaje/)[0] as HTMLElement);
    await user.type(within(dialog).getAllByLabelText(/Porcentaje/)[0] as HTMLElement, "60.01");

    await user.click(save(dialog));

    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent(message);
    expect(alert).toHaveTextContent("t-split");
    expect(alert).toHaveFocus();
    expect(within(dialog).getAllByLabelText(/Porcentaje/)[0]).toHaveValue("60.01");
  });

  it.each([
    ["guardian-not-active", admin.problem.guardianNotActive],
    ["student-not-active", admin.problem.studentNotActiveForGuardians],
    ["forbidden", admin.problem.forbidden],
    ["resource-not-found", admin.problem.notFound],
  ] as const)("tells %s in plain words in the dialog", async (code, message) => {
    const { user, dialog } = await openDialog([ROSA]);
    route("put", LINKS, problem(code, code === "forbidden" ? 403 : code === "resource-not-found" ? 404 : 409));

    await user.click(save(dialog));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(message);
    expect(screen.getByRole("dialog", { name: "Encargados de Ana Pérez" })).toBeInTheDocument();
  });

  it("puts a field error of the server under the right row and in the summary", async () => {
    const { user, dialog } = await openDialog([ROSA, LUIS]);
    route("put", LINKS, problem("validation-failed", 400, { errors: [{ field: "links[1].financialResponsibilityPercent", reason: "decimal-max" }] }));

    await user.click(save(dialog));

    const luis = await within(dialog).findByRole("group", { name: "Luis Paz · P-000002" });
    expect(within(luis).getByLabelText(/Porcentaje/)).toHaveAccessibleDescription(expect.stringContaining(admin.field.percentRange));
    expect(await within(dialog).findByText("Hay 1 campo con error.")).toBeInTheDocument();
  });

  it("waits after a 429 and does not send again during the wait", async () => {
    const { user, dialog } = await openDialog([ROSA]);
    route("put", LINKS, { ...problem("too-many-requests", 429), headers: { "Retry-After": "30" } });

    await user.click(save(dialog));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent(admin.problem.rateLimited);
    await user.click(save(dialog));

    expect(putLinks()).toHaveLength(1);
  });
});
