import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { File as NodeFile } from "node:buffer";
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

// jsdom has its own FormData and File, and the fetch of Node (undici) refuses to serialize them: a request with one fails as if
// the network were down. A real browser sends them as they are. So the tests get the FormData and the File of Node, which the
// fetch of the tests understands (the test server of MSW sits in front of it). The FormData is read from a response, the one
// place Node hands its class out in an environment where the global is jsdom's.
const probe = new Response('--x\r\nContent-Disposition: form-data; name="a"\r\n\r\n1\r\n--x--\r\n', {
  headers: { "Content-Type": "multipart/form-data; boundary=x" },
});
globalThis.FormData = (await probe.formData()).constructor as typeof FormData;
globalThis.File = NodeFile as unknown as typeof File;
