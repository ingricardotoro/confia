import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { File as NodeFile } from "node:buffer";
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

// jsdom has its own FormData and File, and the fetch of Node (undici) refuses to serialize them: a request with one fails as if
// the network were down. A real browser sends them as they are. So the tests get the FormData and the File of Node, which the
// fetch of the tests understands (the test server of MSW sits in front of it). The FormData is read from a response, the one
// place Node hands its class out in an environment where the global is jsdom's.
const probe = new Response('--x\r\nContent-Disposition: form-data; name="a"\r\n\r\n1\r\n--x--\r\n', {
  headers: { "Content-Type": "multipart/form-data; boundary=x" },
});
globalThis.FormData = (await probe.formData()).constructor as typeof FormData;
globalThis.File = NodeFile as unknown as typeof File;
