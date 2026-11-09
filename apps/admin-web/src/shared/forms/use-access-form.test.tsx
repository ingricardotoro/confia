import { act, fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AccessFormHarness, type AccessFormHarnessProps } from "../../test/access-form-harness.tsx";
import { renderWithAdminI18n } from "../../test/render.tsx";
import type { AccessFailure } from "./access-failure.ts";

// admin-web-staff-sign-in, task 2.2 (E3; SS37, SS39, SS40, AW51). The hook is tested through the harness, the way
// a screen renders it. The texts the harness shows are written here, in the test.
const LABELS = { identifier: "Correo electrónico", password: "Contraseña", submit: "Enviar", live: "Estado del envío" } as const;
type Submit = AccessFormHarnessProps["submit"];

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function renderForm(submit: Submit, validate?: AccessFormHarnessProps["validate"]) {
  const user = userEvent.setup();
  renderWithAdminI18n(<AccessFormHarness submit={submit} labels={LABELS} {...(validate === undefined ? {} : { validate })} />);
  return {
    user,
    identifier: () => screen.getByRole("textbox", { name: /Correo electrónico/ }),
    password: () => screen.getByLabelText(/Contraseña/),
    button: () => screen.getByRole("button", { name: /Enviar/ }),
    form: () => screen.getByRole("button", { name: /Enviar/ }).closest("form") as HTMLFormElement,
  };
}

describe("useAccessForm: a failed submit", () => {
  it("SS37: sends nothing when a field is wrong, shows the summary with a link per error and focuses the summary", async () => {
    const submit = vi.fn<Submit>(() => Promise.resolve(undefined));
    const { user, button, identifier, password } = renderForm(submit);

    await user.click(button());

    expect(submit).not.toHaveBeenCalled();
    const summary = screen.getByRole("group");
    expect(summary).toHaveFocus();
    expect(within(summary).getAllByRole("link")).toHaveLength(2);
    expect(identifier()).toHaveAttribute("aria-invalid", "true");
    expect(password()).toHaveAttribute("aria-invalid", "true");
    expect(identifier()).toHaveAccessibleDescription("Este dato es obligatorio.");
  });

  it("SS37: an error from leaving a field shows under the field and no summary", async () => {
    const { user, identifier } = renderForm(vi.fn<Submit>(() => Promise.resolve(undefined)));

    await user.click(identifier());
    await user.tab();

    expect(screen.queryByRole("group")).not.toBeInTheDocument();
    expect(identifier()).toHaveAttribute("aria-invalid", "true");
    expect(identifier()).not.toHaveFocus();
  });

  it("revalidates on every key only a field that is already marked", async () => {
    const tooLong: AccessFormHarnessProps["validate"] = (values) => ({
      ...(values.identifier.length > 3 ? { identifier: "too-long" as const } : {}),
    });
    const { user, identifier } = renderForm(vi.fn<Submit>(() => Promise.resolve(undefined)), tooLong);

    await user.type(identifier(), "abcd");
    expect(identifier()).not.toHaveAttribute("aria-invalid");

    await user.tab();
    expect(identifier()).toHaveAttribute("aria-invalid", "true");
    expect(identifier()).toHaveAccessibleDescription("Este dato es demasiado largo.");

    await user.click(identifier());
    await user.keyboard("{Backspace}");
    expect(identifier()).not.toHaveAttribute("aria-invalid");
  });

  it("the summary link takes the focus to its field", async () => {
    const { user, button, password } = renderForm(vi.fn<Submit>(() => Promise.resolve(undefined)));

    await user.click(button());
    await user.click(within(screen.getByRole("group")).getAllByRole("link")[1] as HTMLElement);

    expect(password()).toHaveFocus();
  });

  it("AW51: shows a failure of fields from the server under the field and in the summary, with an unknown field as text", async () => {
    const failure: AccessFailure = {
      kind: "fields",
      errors: [
        { field: "password", reason: "max" },
        { field: "nickname", reason: "size" },
      ],
    };
    const { user, identifier, password, button } = renderForm(() => Promise.resolve(failure));

    await user.type(identifier(), "ana");
    await user.type(password(), "secreto");
    await user.click(button());

    expect(password()).toHaveAccessibleDescription("Este dato es demasiado largo.");
    const summary = screen.getByRole("group");
    expect(summary).toHaveFocus();
    expect(within(summary).getAllByRole("listitem")).toHaveLength(2);
    expect(within(summary).getAllByRole("link")).toHaveLength(1);
    expect(summary).toHaveTextContent("Uno de los datos enviados no es válido.");
    expect(summary).not.toHaveTextContent("secreto");
  });

  it("focuses the alert when the failure has no field, and keeps what was typed", async () => {
    const failure: AccessFailure = { kind: "message", key: "problem.authenticationFailed" };
    const { user, identifier, password, button } = renderForm(() => Promise.resolve(failure));

    await user.type(identifier(), "ana@example.test");
    await user.type(password(), "una-clave");
    await user.click(button());

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("El correo o la contraseña no son correctos.");
    expect(alert).toHaveFocus();
    expect(identifier()).toHaveValue("ana@example.test");
    expect(password()).toHaveValue("una-clave");
  });

  it("shows the generic message with the trace id of an unexpected failure", async () => {
    const { user, identifier, password, button } = renderForm(() => Promise.resolve({ kind: "unexpected", traceId: "TRACE-1" }));

    await user.type(identifier(), "ana");
    await user.type(password(), "x");
    await user.click(button());

    expect(screen.getByRole("alert")).toHaveTextContent(/Ocurrió un error inesperado/);
    expect(screen.getByText("TRACE-1")).toBeInTheDocument();
  });

  it("clears the previous alert when the next submit starts", async () => {
    const submit = vi
      .fn<Submit>()
      .mockResolvedValueOnce({ kind: "message", key: "problem.network" })
      .mockResolvedValueOnce(undefined);
    const { user, identifier, password, button } = renderForm(submit);

    await user.type(identifier(), "ana");
    await user.type(password(), "x");
    await user.click(button());
    expect(screen.getByRole("alert")).toBeInTheDocument();
    await user.click(button());

    expect(submit).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("forgets what was typed when the form goes away", async () => {
    const first = renderWithAdminI18n(<AccessFormHarness submit={() => Promise.resolve(undefined)} labels={LABELS} />);
    await userEvent.setup().type(screen.getByRole("textbox", { name: /Correo electrónico/ }), "ana");
    first.unmount();

    renderWithAdminI18n(<AccessFormHarness submit={() => Promise.resolve(undefined)} labels={LABELS} />);

    expect(screen.getByRole("textbox", { name: /Correo electrónico/ })).toHaveValue("");
  });
});

describe("useAccessForm: a submit in flight", () => {
  it("SS39: keeps the button focusable and enabled, busy, and shows the wait text in a polite region", async () => {
    const held = deferred<AccessFailure | undefined>();
    const { user, identifier, password, button } = renderForm(() => held.promise);
    await user.type(identifier(), "ana");
    await user.type(password(), "x");

    await user.click(button());

    expect(button()).toHaveAttribute("aria-busy", "true");
    expect(button()).not.toBeDisabled();
    expect(screen.getByRole("status", { name: LABELS.live })).toHaveTextContent("Verificando sus datos. Esto puede tardar hasta 30 segundos.");

    await act(async () => {
      held.resolve(undefined);
      await held.promise;
    });
    expect(button()).not.toHaveAttribute("aria-busy");
    expect(screen.getByRole("status", { name: LABELS.live })).toBeEmptyDOMElement();
  });

  it("SS40: a second activation of the button, by mouse or keyboard, calls submit once", async () => {
    const held = deferred<AccessFailure | undefined>();
    const submit = vi.fn<Submit>(() => held.promise);
    const { user, identifier, password, button } = renderForm(submit);
    await user.type(identifier(), "ana");
    await user.type(password(), "x");

    await user.click(button());
    await user.click(button());
    button().focus();
    await user.keyboard("{Enter}");
    await user.keyboard(" ");

    expect(submit).toHaveBeenCalledTimes(1);
  });

  it("SS40: a submit with Enter in a field, and one that does not go through the button, call submit once", async () => {
    const held = deferred<AccessFailure | undefined>();
    const submit = vi.fn<Submit>(() => held.promise);
    const { user, identifier, password, form } = renderForm(submit);
    await user.type(identifier(), "ana");
    await user.type(password(), "x{Enter}");

    await user.keyboard("{Enter}");
    fireEvent.submit(form());

    expect(submit).toHaveBeenCalledTimes(1);
  });

  it("sends the normalized values, not the typed ones", async () => {
    const submit = vi.fn<Submit>(() => Promise.resolve(undefined));
    const { user, identifier, password, button } = renderForm(submit);
    await user.type(identifier(), "  ana@example.test  ");
    await user.type(password(), " x ");

    await user.click(button());

    expect(submit).toHaveBeenCalledWith({ identifier: "ana@example.test", password: " x " });
  });

  it("lets a new submit through once the previous one has ended", async () => {
    const submit = vi.fn<Submit>(() => Promise.resolve({ kind: "message", key: "problem.network" }));
    const { user, identifier, password, button } = renderForm(submit);
    await user.type(identifier(), "ana");
    await user.type(password(), "x");

    await user.click(button());
    await user.click(button());

    expect(submit).toHaveBeenCalledTimes(2);
  });
});

describe("useAccessForm: a 429", () => {
  const limited: AccessFailure = { kind: "rate-limited", retryAfterSeconds: 15 };

  async function typeAndLimit(submit: Submit) {
    const form = renderForm(submit);
    await form.user.type(form.identifier(), "ana");
    await form.user.type(form.password(), "x");
    // Timers are simulated only from here, so typing keeps its real timing.
    vi.useFakeTimers();
    fireEvent.submit(form.form());
    await act(async () => {
      await Promise.resolve();
    });
    return form;
  }

  afterEach(() => {
    vi.useRealTimers();
  });

  it("SS43: shows the fixed text in the alert and the seconds apart from it, counting down", async () => {
    await typeAndLimit(vi.fn<Submit>(() => Promise.resolve(limited)));

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Hizo demasiadas solicitudes seguidas. Espere unos segundos e intente de nuevo.");
    expect(alert).not.toHaveTextContent(/\d+ s/);
    expect(screen.getByText("Puede intentar de nuevo en 15 s.")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(screen.getByText("Puede intentar de nuevo en 13 s.")).toBeInTheDocument();
    expect(alert).toHaveTextContent("Hizo demasiadas solicitudes seguidas. Espere unos segundos e intente de nuevo.");
  });

  it("SS45: a submit during the wait does not call submit, does not restart the count, and returns the focus to the alert", async () => {
    const submit = vi.fn<Submit>(() => Promise.resolve(limited));
    const { identifier, form, button } = await typeAndLimit(submit);
    act(() => {
      vi.advanceTimersByTime(4000);
    });
    identifier().focus();

    fireEvent.submit(form());

    expect(submit).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Puede intentar de nuevo en 11 s.")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveFocus();
    expect(button()).not.toBeDisabled();
  });

  it("SS46: when the wait ends, the polite region says so and a new submit goes through", async () => {
    const submit = vi.fn<Submit>(() => Promise.resolve(limited));
    const { form } = await typeAndLimit(submit);

    act(() => {
      vi.advanceTimersByTime(15_000);
    });

    expect(screen.getByRole("status", { name: LABELS.live })).toHaveTextContent("Ya puede intentar de nuevo.");
    expect(screen.queryByText(/Puede intentar de nuevo en/)).not.toBeInTheDocument();

    fireEvent.submit(form());
    await act(async () => {
      await Promise.resolve();
    });
    expect(submit).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("status", { name: LABELS.live })).toBeEmptyDOMElement();
  });
});
