import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Vitest globals are off, so Testing Library cannot register its own cleanup.
afterEach(cleanup);

// jsdom has no ResizeObserver, and the checkbox of the design system measures its hidden input with one inside a form.
// A control that measures nothing is enough: no test here depends on a size.
if (!("ResizeObserver" in globalThis)) {
  globalThis.ResizeObserver = class {
    observe(): void {
      // Nothing is measured.
    }
    unobserve(): void {
      // Nothing is measured.
    }
    disconnect(): void {
      // Nothing is measured.
    }
  };
}
