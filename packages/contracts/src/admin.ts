// Public entry point of the administrative contract: the only path "@confia/contracts/admin"
// resolves to. Hand-written and nothing more than a re-export, so the generated files behind it can
// change shape without any consumer importing them directly. The operations module joins here with
// the first administrative endpoint.
export * from "./generated/admin/schemas.js";
