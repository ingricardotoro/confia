import { Plus } from "lucide-react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import {
  Badge,
  Button,
  Checkbox,
  EmptyState,
  ErrorState,
  FormError,
  FormField,
  IconButton,
  Input,
  Label,
  MoneyAmount,
  RadioGroup,
  RadioGroupItem,
  Separator,
  Skeleton,
  Spinner,
  StatusBadge,
  Textarea,
} from "./index";
import * as api from "./index";
import { renderWithI18n } from "./test/render";

// design-system, requirement "La presentación de packages/ui no obtiene datos": every component renders
// from its props alone, with no TanStack Query provider and with every way of making a request replaced
// by a function that throws. A component that asked for anything would fail here.
const REQUESTS = ["fetch", "XMLHttpRequest", "WebSocket", "EventSource"] as const;

const FIXTURES: Record<string, ReactElement> = {
  Badge: <Badge>Etiqueta</Badge>,
  Button: <Button>Guardar</Button>,
  Checkbox: <Checkbox aria-label="Aceptar" />,
  EmptyState: <EmptyState title="Sin cargos" action={{ label: "Agregar", onAction: () => undefined }} />,
  ErrorState: <ErrorState title="No pudimos cargar" onRetry={() => undefined} />,
  FormError: <FormError>Ingrese un monto</FormError>,
  FormField: <FormField label="Monto">{(control) => <Input {...control} />}</FormField>,
  IconButton: (
    <IconButton label="Agregar">
      <Plus />
    </IconButton>
  ),
  Input: <Input aria-label="Nombre" />,
  Label: <Label>Nombre</Label>,
  MoneyAmount: <MoneyAmount value={{ amount: "3200.00", currency: "HNL" }} />,
  RadioGroup: (
    <RadioGroup aria-label="Método">
      <RadioGroupItem value="cash" aria-label="Efectivo" />
    </RadioGroup>
  ),
  Separator: <Separator />,
  Skeleton: <Skeleton />,
  Spinner: <Spinner />,
  StatusBadge: <StatusBadge status="paid" />,
  Textarea: <Textarea aria-label="Nota" />,
};

// A plain function, not an arrow, so it can be called with new as well (WebSocket, EventSource).
const thrower = (name: string) =>
  function () {
    throw new Error(`${name} was called: packages/ui never makes requests`);
  };

beforeEach(() => {
  for (const name of REQUESTS) vi.stubGlobal(name, thrower(name));
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe("the components without a data layer", () => {
  it("DS61: the fixtures cover every exported component, so none escapes the check", () => {
    const components = Object.keys(api).filter((name) => /^[A-Z]/.test(name) && name !== "RadioGroupItem");

    expect(Object.keys(FIXTURES).sort()).toEqual(components.sort());
  });

  it("DS61: the stubs really throw", () => {
    expect(() => globalThis.fetch("/api")).toThrow("fetch was called");
    expect(() => new globalThis.WebSocket("ws://localhost")).toThrow("WebSocket was called");
  });

  it.each(Object.entries(FIXTURES))("DS61: renders %s from props alone, with no query provider", (_name, element) => {
    expect(() => renderWithI18n(element)).not.toThrow();
  });
});
