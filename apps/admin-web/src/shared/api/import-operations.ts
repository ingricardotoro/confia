import {
  ImportBatchPageResponse,
  ImportRunResponse,
  type ImportBatchPageResponseOutput,
  type ImportRunResponseOutput,
} from "@confia/contracts/admin";
import { request, type ApiResult } from "./http.ts";
import { resourceClient, type PageParams } from "./resource-client.ts";

/**
 * The operations of `/api/v1/imports`. Each takes the access token as a parameter: this module knows no session. The two
 * that read a file (one pair per kind: students, guardians, guardian links and enrollments) send it as a `multipart/form-data` body in the part `file` and keep nothing of it: the `FormData` lives
 * for the call, and what comes back holds only counts and the line, field and reason of each rejection (never a value).
 * None moves money, so none sends an `Idempotency-Key`; an import is repeatable by design (the same file simulates again, and
 * applying it again creates nothing new). The history is paged by the server.
 */
const { list } = resourceClient("/api/v1");

const bearer = (token: string): Readonly<Record<string, string>> => ({ Authorization: `Bearer ${token}` });

function formOf(file: File): FormData {
  const form = new FormData();
  form.append("file", file);
  return form;
}

/** What a file loads, as the routes name it: `students`, `guardians`, `guardian-links` or `enrollments`. */
export type ImportRoute = "students" | "guardians" | "guardian-links" | "enrollments";

/** Validates the file and counts what an apply would do. Writes nothing but its own batch. */
export const simulateImport = (route: ImportRoute, token: string, file: File): Promise<ApiResult<ImportRunResponseOutput>> =>
  request({ method: "POST", path: `/api/v1/imports/${route}/simulate`, headers: bearer(token), body: formOf(file), schema: ImportRunResponse });

/** Creates what the valid rows of the same file that was simulated describe. */
export const applyImport = (route: ImportRoute, token: string, file: File): Promise<ApiResult<ImportRunResponseOutput>> =>
  request({ method: "POST", path: `/api/v1/imports/${route}/apply`, headers: bearer(token), body: formOf(file), schema: ImportRunResponse });

/** The batches of the institution, newest first. */
export const listImportBatches = (token: string, params: PageParams): Promise<ApiResult<ImportBatchPageResponseOutput>> =>
  list("imports", ImportBatchPageResponse, token, params);
