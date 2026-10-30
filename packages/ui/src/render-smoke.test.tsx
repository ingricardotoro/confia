import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

// Proves the unit project collects *.test.tsx and renders React in jsdom (BX20). The component is a
// stand-in; real components arrive with their own tests.
function Probe() {
  return <button type="button">probe</button>;
}

describe("the unit project", () => {
  it("renders a React component in jsdom and finds it by role", () => {
    render(<Probe />);

    expect(screen.getByRole("button", { name: "probe" })).toBeInTheDocument();
  });
});
