import { describe, expect, it } from "vitest";
import { fieldState } from "./field-state";

// docs/ui-ux/02 section 6: the states of a text field live in one place, shared by Input and the
// Textarea of 5.1.
describe("fieldState", () => {
  it("shows the keyboard focus ring through the shared focus constant", () => {
    expect(fieldState).toContain("focus-visible:outline-border-focus");
  });

  it("keeps read-only selectable: no pointer-events or cursor block, unlike disabled", () => {
    expect(fieldState).toContain("read-only:not-disabled:bg-bg-canvas");
    expect(fieldState).not.toContain("read-only:cursor-not-allowed");
    expect(fieldState).not.toContain("read-only:pointer-events-none");
    expect(fieldState).toContain("disabled:cursor-not-allowed");
  });

  it("R3-readonly-matches-disabled: never styles read-only without excluding disabled, since :read-only matches both", () => {
    const readOnlyUtilities = fieldState.split(" ").filter((c) => c.startsWith("read-only:"));

    expect(readOnlyUtilities.length).toBeGreaterThan(0);
    for (const utility of readOnlyUtilities) {
      expect(utility).toMatch(/^read-only:not-disabled:/);
    }
  });

  it("draws the error border for aria-invalid, from a component token", () => {
    expect(fieldState).toContain("aria-invalid:border-(--input-invalid-border)");
  });
});
