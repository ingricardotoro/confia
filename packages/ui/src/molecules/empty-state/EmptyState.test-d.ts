import { describe, expectTypeOf, it } from "vitest";
import type { EmptyStateProps } from "./EmptyState";

// ADR-0018 static check (DS25): an empty state without the action that resolves it must not
// compile. Each @ts-expect-error is a permanent, deliberate violation; if the line stops failing,
// tsc reports an unused directive and this file turns red.
describe("EmptyState props", () => {
  it("requires the action", () => {
    // @ts-expect-error ADR-0018: deliberate violation, the action prop is required
    const withoutAction: EmptyStateProps = { title: "Sin cargos" };

    const withAction: EmptyStateProps = { title: "Sin cargos", action: { label: "Agregar", onAction: () => undefined } };
    expectTypeOf(withAction.action.onAction).toEqualTypeOf<() => void>();
    expectTypeOf(withoutAction).toExtend<EmptyStateProps>();
  });

  it("requires the label of the action", () => {
    // @ts-expect-error ADR-0018: deliberate violation, the action needs a label
    const withoutLabel: EmptyStateProps = { title: "Sin cargos", action: { onAction: () => undefined } };
    expectTypeOf(withoutLabel).toExtend<EmptyStateProps>();
  });
});
