import { readProblem, type ApiProblem } from "./problem.ts";

/**
 * The outcome of a request, as a value: the transport never throws on a response or on a failure of
 * the network (design.md D6).
 */
export type ApiResult<T> =
  | {
      readonly kind: "ok";
      readonly status: number;
      readonly data: T;
      /** `Date.parse` of the `Date` header; `undefined` when it is absent, empty or not a valid HTTP date (AW65 to AW67). */
      readonly serverDate: number | undefined;
      /** The local `Date.now()` when the response arrived, so the session can measure its clock against the server's (H3). */
      readonly receivedAt: number;
    }
  /** Includes an unknown code and a body that cannot be read. */
  | { readonly kind: "problem"; readonly problem: ApiProblem }
  /** No HTTP status: the network failed or the time limit ran out. */
  | { readonly kind: "network" }
  /** A 2xx whose body does not meet the schema. It carries no part of that body. */
  | { readonly kind: "unexpected-response"; readonly status: number };

/** What a generated Zod schema offers, so `shared/api` takes one without depending on Zod. */
export interface ResponseSchema<T> {
  safeParse(input: unknown): { readonly success: true; readonly data: T } | { readonly success: false };
}

export interface RequestOptions {
  readonly method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  /** Always `/api/...`: a relative path, never a scheme or a host (AW45). */
  readonly path: string;
  /** Extra headers, such as `Authorization` or the CSRF value: this module knows no session. */
  readonly headers?: Readonly<Record<string, string>>;
  /** JSON by default; a `FormData` travels as multipart (the import of a file). */
  readonly body?: unknown;
}

/** The limit is ours, not the server's: a slow answer is awaited, and a lost one is given up on. */
const REQUEST_TIMEOUT_MS = 60_000;
const RELATIVE_API_PATH = /^\/api\/[^\s\\]*$/;
/** A `.` or `..` segment, also percent-encoded: the URL parser resolves both, and `..` would climb out of /api/. */
const DOT_SEGMENT = /(^|\/)(\.|%2e){1,2}(\/|$)/i;

function assertRelativeApiPath(path: string): void {
  if (!RELATIVE_API_PATH.test(path) || DOT_SEGMENT.test(path)) {
    throw new TypeError("An API path must be relative, start with /api/ and hold no dot segments.");
  }
}

function initOf(options: RequestOptions, signal: AbortSignal): RequestInit {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  // A form is sent as it is and without a `Content-Type`: the browser writes the multipart one with its boundary.
  const isForm = options.body instanceof FormData;
  if (options.body !== undefined && !isForm && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  return {
    method: options.method,
    headers,
    credentials: "same-origin",
    signal,
    ...(options.body === undefined ? {} : { body: isForm ? options.body : JSON.stringify(options.body) }),
  };
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
/** The HTTP date a server sends in `Date`: `Tue, 10 Nov 2026 10:00:00 GMT` (RFC 9110, IMF-fixdate). */
const HTTP_DATE = /^(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), (\d{2}) ([A-Z][a-z]{2}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$/;

/**
 * The instant the `Date` header names, or `undefined`. A missing, empty, unreadable or impossible header
 * (`31 Feb`, hour 25) is never `NaN` and never an error: `Date.parse` alone would roll 31 Feb over to
 * March, so the parts are read back and must be the ones written (AW65 to AW67).
 */
function serverDateOf(response: Response): number | undefined {
  const parts = HTTP_DATE.exec(response.headers.get("Date") ?? "");
  const month = MONTHS.indexOf(parts?.[2] ?? "");
  if (parts === null || month < 0) return undefined;
  // `Number(undefined)` is `NaN`, which the round trip below refuses, so no group needs a default.
  const [day, year, hour, minute, second] = [parts[1], parts[3], parts[4], parts[5], parts[6]].map(Number);
  const instant = Date.UTC(year as number, month, day, hour, minute, second);
  const read = new Date(instant);
  const coherent =
    read.getUTCFullYear() === year &&
    read.getUTCMonth() === month &&
    read.getUTCDate() === day &&
    read.getUTCHours() === hour &&
    read.getUTCMinutes() === minute &&
    read.getUTCSeconds() === second;
  return coherent ? instant : undefined;
}

/** The fields every ok result carries besides its data. */
function arrival(response: Response): { readonly status: number; readonly serverDate: number | undefined; readonly receivedAt: number } {
  return { status: response.status, serverDate: serverDateOf(response), receivedAt: Date.now() };
}

/**
 * The one `fetch` of the app. A timeout is the network result and is never repeated here: the server may
 * already have rotated a token, so repeating is the caller's decision. The timer runs until the body has
 * been read, so an answer that stalls halfway is also given up on.
 */
async function exchange<T>(
  options: RequestOptions,
  onSuccess: (response: Response) => Promise<ApiResult<T>>,
): Promise<ApiResult<T>> {
  assertRelativeApiPath(options.path);
  const controller = new AbortController();
  const timer = setTimeout(() => {
    controller.abort();
  }, REQUEST_TIMEOUT_MS);
  try {
    let response: Response;
    try {
      response = await fetch(options.path, initOf(options, controller.signal));
    } catch {
      return { kind: "network" };
    }
    const result: ApiResult<T> =
      response.status >= 200 && response.status < 300
        ? await onSuccess(response)
        : { kind: "problem", problem: await readProblem(response) };
    return controller.signal.aborted && result.kind !== "ok" ? { kind: "network" } : result;
  } finally {
    clearTimeout(timer);
  }
}

/** A request whose 2xx body must meet `schema`; anything else is a problem, the network or an unexpected response. */
export function request<T>(options: RequestOptions & { readonly schema: ResponseSchema<T> }): Promise<ApiResult<T>> {
  return exchange(options, async (response) => {
    const unexpected = { kind: "unexpected-response", status: response.status } as const;
    let body: unknown;
    try {
      body = JSON.parse(await response.text());
    } catch {
      return unexpected;
    }
    const parsed = options.schema.safeParse(body);
    return parsed.success ? { kind: "ok", ...arrival(response), data: parsed.data } : unexpected;
  });
}

/** A request with no body to read, such as a `204`. The caller checks `status` when only one is acceptable. */
export function requestEmpty(options: RequestOptions): Promise<ApiResult<undefined>> {
  return exchange(options, (response) => Promise.resolve({ kind: "ok", ...arrival(response), data: undefined }));
}
