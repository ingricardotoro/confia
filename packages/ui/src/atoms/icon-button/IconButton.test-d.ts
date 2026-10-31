import { describe, expectTypeOf, it } from "vitest";
import type { IconButtonProps } from "./IconButton";

// ADR-0018 static check (DS15): an icon-only button without an accessible name must not compile.
// Each @ts-expect-error below is a permanent, deliberate violation; if the line stops failing, tsc
// reports an unused directive and this file turns red.
describe("IconButton props", () => {
  it("requires the accessible label", () => {
    expectTypeOf<IconButtonProps["label"]>().toEqualTypeOf<string>();

    // @ts-expect-error ADR-0018: deliberate violation, the accessible label prop is required
    const withoutLabel: IconButtonProps = { children: null };

    const withLabel: IconButtonProps = { label: "Agregar cargo", children: null };
    expectTypeOf(withLabel.label).toBeString();
    expectTypeOf(withoutLabel).toExtend<IconButtonProps>();
  });

  it("does not let the caller replace the label with aria-label", () => {
    // @ts-expect-error ADR-0018: deliberate violation, the name comes only from the label prop
    const own: IconButtonProps = { label: "Agregar cargo", "aria-label": "Otro", children: null };
    expectTypeOf(own).toExtend<IconButtonProps>();
  });
});
