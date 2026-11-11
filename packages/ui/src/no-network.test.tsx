import { act } from "@testing-library/react";
import { Plus } from "lucide-react";
import { useEffect } from "react";
import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  Alert,
  Badge,
  Button,
  Checkbox,
  Dialog,
  EmptyState,
  ErrorState,
  FormError,
  FormErrorSummary,
  FormField,
  IconButton,
  Input,
  Label,
  MoneyAmount,
  Pagination,
  RadioGroup,
  RadioGroupItem,
  Select,
  Separator,
  Skeleton,
  Spinner,
  StatusBadge,
  Table,
  Textarea,
} from "./index";
import * as api from "./index";
import { renderWithI18n } from "./test/render";

// design-system, requirement "La presentación de packages/ui no obtiene datos": every component renders
// from its props alone, with no TanStack Query provider and with every way of making a request replaced
// by a spy that throws. The throw alone proves little: a request made after the render, from an effect
// or a timer, rejects a promise that nobody awaits and the test would stay green. So the spies are also
// asked, once the pending work has run, whether any of them was ever called.
const REQUESTS = ["fetch", "XMLHttpRequest", "WebSocket", "EventSource"] as const;

const FIXTURES: Record<string, ReactElement> = {
  Alert: <Alert>Aviso</Alert>,
  Badge: <Badge>Etiqueta</Badge>,
  Button: <Button>Guardar</Button>,
  Checkbox: <Checkbox aria-label="Aceptar" />,
  Dialog: (
    <Dialog open onOpenChange={() => undefined} title="Nueva modalidad" description="Registre el nombre.">
      <Input aria-label="Nombre" />
    </Dialog>
  ),
  EmptyState: <EmptyState title="Sin cargos" action={{ label: "Agregar", onAction: () => undefined }} />,
  ErrorState: <ErrorState title="No pudimos cargar" onRetry={() => undefined} />,
  FormError: <FormError>Ingrese un monto</FormError>,
  FormErrorSummary: <FormErrorSummary errors={[{ controlId: "amount", message: "Ingrese un monto" }]} />,
  FormField: <FormField label="Monto">{(control) => <Input {...control} />}</FormField>,
  IconButton: (
    <IconButton label="Agregar">
      <Plus />
    </IconButton>
  ),
  Input: <Input aria-label="Nombre" />,
  Label: <Label>Nombre</Label>,
  MoneyAmount: <MoneyAmount value={{ amount: "3200.00", currency: "HNL" }} />,
  Pagination: <Pagination page={0} size={50} total={120} onPageChange={() => undefined} />,
  RadioGroup: (
    <RadioGroup aria-label="Método">
      <RadioGroupItem value="cash" aria-label="Efectivo" />
    </RadioGroup>
  ),
  Select: (
    <Select aria-label="Modalidad">
      <option value="a">Regular</option>
    </Select>
  ),
  Separator: <Separator />,
  Skeleton: <Skeleton />,
  Spinner: <Spinner />,
  StatusBadge: <StatusBadge status="paid" />,
  Table: (
    <Table
      caption="Modalidades"
      columns={[{ id: "name", header: "Nombre", cell: (row: { name: string }) => row.name }]}
      rows={[{ name: "Regular" }]}
      getRowId={(row) => row.name}
      empty={{ title: "Sin modalidades", action: { label: "Crear", onAction: () => undefined } }}
    />
  ),
  Textarea: <Textarea aria-label="Nota" />,
};

// A plain function, not an arrow, so it can be called with new as well (WebSocket, EventSource).
const thrower = (name: string) =>
  function () {
    throw new Error(`${name} was called: packages/ui never makes requests`);
  };

const spies = new Map<string, ReturnType<typeof vi.fn>>();

beforeEach(() => {
  spies.clear();
  for (const name of REQUESTS) {
    const spy = vi.fn(thrower(name));
    spies.set(name, spy);
    vi.stubGlobal(name, spy);
  }
});
afterEach(() => {
  vi.unstubAllGlobals();
});

/** Renders the element, lets the microtasks and the short timers it started run, and names what was called. */
async function requestsMadeBy(element: ReactElement): Promise<string[]> {
  renderWithI18n(element);
  await act(async () => {
    await Promise.resolve();
    await new Promise((resolve) => setTimeout(resolve, 25));
  });
  return REQUESTS.filter((name) => (spies.get(name)?.mock.calls.length ?? 0) > 0);
}

// Swallows its own rejection, the way a careless data hook does, so only the spy can tell.
function LeaksARequest() {
  useEffect(() => {
    void Promise.resolve()
      .then(() => globalThis.fetch("/api/v1/payments"))
      .catch(() => undefined);
  }, []);
  return null;
}

describe("the components without a data layer", () => {
  it("DS61: the fixtures cover every exported component, so none escapes the check", () => {
    const components = Object.keys(api).filter((name) => /^[A-Z]/.test(name) && name !== "RadioGroupItem");

    expect(Object.keys(FIXTURES).sort()).toEqual(components.sort());
  });

  it("DS61: the stubs really throw", () => {
    expect(() => globalThis.fetch("/api")).toThrow("fetch was called");
    expect(() => new globalThis.WebSocket("ws://localhost")).toThrow("WebSocket was called");
  });

  it("DS61: a request made after the render, whose failure is swallowed, is still seen by the check", async () => {
    expect(await requestsMadeBy(<LeaksARequest />)).toEqual(["fetch"]);
  });

  it.each(Object.entries(FIXTURES))(
    "DS61: renders %s from props alone, with no query provider, and never makes a request",
    async (_name, element) => {
      expect(await requestsMadeBy(element)).toEqual([]);
    },
  );
});
