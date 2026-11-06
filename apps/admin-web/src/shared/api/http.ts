import { readProblem, type ApiProblem } from "./problem.ts";

/**
 * The outcome of a request, as a value: the transport never throws on a response or on a failure of
 * the network (design.md D6).
 */
export type ApiResult<T> =
  | { readonly kind: "ok"; readonly status: number; readonly data: T }
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
  if (options.body !== undefined && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  return {
    method: options.method,
    headers,
    credentials: "same-origin",
    signal,
    ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
  };
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
    return parsed.success ? { kind: "ok", status: response.status, data: parsed.data } : unexpected;
  });
}

/** A request with no body to read, such as a `204`. The caller checks `status` when only one is acceptable. */
export function requestEmpty(options: RequestOptions): Promise<ApiResult<undefined>> {
  return exchange(options, (response) => Promise.resolve({ kind: "ok", status: response.status, data: undefined }));
}
