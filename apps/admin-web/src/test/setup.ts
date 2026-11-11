import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Vitest globals are off, so Testing Library cannot register its own cleanup.
afterEach(cleanup);

// jsdom has no ResizeObserver. Radix measures a checkbox that sits inside a form with one (the "remove the document"
// box of the student form does), so the tests get a stand-in that never reports a size.
if (!Reflect.has(globalThis, "ResizeObserver")) {
  globalThis.ResizeObserver = class {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  };
}
