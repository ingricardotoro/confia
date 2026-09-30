// Public entry point of the portal contract: the only path "@confia/contracts/portal" resolves to.
// Hand-written and nothing more than a re-export, so the generated files behind it can change shape
// without any consumer importing them directly. The operations module joins here with the first
// portal endpoint.
export * from "./generated/portal/schemas.js";
