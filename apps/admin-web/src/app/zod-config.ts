import { config } from "zod";

// Imported first by main.tsx (AW12). Zod 4 tries `Function("")` the first time a schema is used to find out
// whether it may compile validators. Under a CSP without 'unsafe-eval' the attempt fails and Zod copes, but
// the browser still reports a securitypolicyviolation, and AW12 allows none. `jitless` skips the attempt.
// It must run before any schema is built or parsed, so it lives in a module of its own with no other import.
config({ jitless: true });
