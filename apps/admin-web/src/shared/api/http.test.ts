import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { request, requestEmpty, type ResponseSchema } from "./http.ts";

// admin-web-foundations-and-session, task 4.1 (AW43, AW44, AW45, design.md D6). The transport never
// throws, opens the network from one place, and gives a slow answer the full minute. Timers are
// simulated: no test waits 31 or 60 real seconds.
interface Greeting {
  readonly hello: string;
}

const greetingSchema: ResponseSchema<Greeting> = {
  safeParse: (input) =>
    typeof input === "object" && input !== null && "hello" in input && typeof input.hello === "string"
      ? { success: true, data: { hello: input.hello } }
      : { success: false },
};

const fetchMock = vi.fn<typeof fetch>();

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("a successful request", () => {
  it("returns the validated data with the status", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ hello: "mundo" }));

    const result = await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });

    expect(result).toMatchObject({ kind: "ok", status: 200, data: { hello: "mundo" } });
  });

  it("sends a relative path, the same origin credentials and the given headers", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ hello: "x" }));

    await request({
      method: "POST",
      path: "/api/v1/greeting",
      headers: { "X-Probe": "1" },
      body: { name: "a" },
      schema: greetingSchema,
    });

    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(url).toBe("/api/v1/greeting");
    expect(init?.method).toBe("POST");
    expect(init?.credentials).toBe("same-origin");
    expect(init?.body).toBe('{"name":"a"}');
    expect(new Headers(init?.headers).get("Content-Type")).toBe("application/json");
    expect(new Headers(init?.headers).get("Accept")).toBe("application/json");
    expect(new Headers(init?.headers).get("X-Probe")).toBe("1");
    expect(new Headers(init?.headers).has("Authorization")).toBe(false);
  });

  it("sends no body and no content type when there is no body", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ hello: "x" }));

    await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });

    const [, init] = fetchMock.mock.calls[0] ?? [];
    expect(init?.body).toBeUndefined();
    expect(new Headers(init?.headers).has("Content-Type")).toBe(false);
  });

  it("requestEmpty accepts a 204 and ignores any body", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    expect(await requestEmpty({ method: "DELETE", path: "/api/v1/sessions/current" })).toMatchObject({
      kind: "ok",
      status: 204,
      data: undefined,
    });
  });
});

// admin-web-shell-and-session-lifecycle, task 1.1 (AW65 to AW67, design.md H3). An ok result tells when
// the server said it was and when the answer arrived, so the session can correct a wrong local clock.
describe("the server date of an ok result", () => {
  const ARRIVED_AT = Date.parse("2026-11-10T09:57:00Z");
  const IMF_FIXDATE = "Tue, 10 Nov 2026 10:00:00 GMT";

  function withDate(date: string | undefined, init: ResponseInit = {}, body: BodyInit | null = JSON.stringify({ hello: "mundo" })): Response {
    const headers = new Headers(init.headers);
    if (date !== undefined) headers.set("Date", date);
    return new Response(body, { status: 200, ...init, headers });
  }

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(ARRIVED_AT);
  });

  it("AW65: exposes the instant of a valid Date header and the local instant of arrival", async () => {
    fetchMock.mockResolvedValue(withDate(IMF_FIXDATE));

    const result = await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });

    expect(result).toEqual({
      kind: "ok",
      status: 200,
      data: { hello: "mundo" },
      serverDate: Date.UTC(2026, 10, 10, 10, 0, 0),
      receivedAt: ARRIVED_AT,
    });
  });

  it("AW65: a second header gives another instant, so the value is read from the response", async () => {
    fetchMock.mockResolvedValue(withDate("Wed, 11 Nov 2026 23:59:59 GMT"));

    const result = await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });

    expect(result).toMatchObject({ kind: "ok", serverDate: Date.UTC(2026, 10, 11, 23, 59, 59) });
  });

  it("AW65: requestEmpty exposes it too", async () => {
    fetchMock.mockResolvedValue(withDate(IMF_FIXDATE, { status: 204 }, null));

    const result = await requestEmpty({ method: "DELETE", path: "/api/v1/sessions/current" });

    expect(result).toEqual({
      kind: "ok",
      status: 204,
      data: undefined,
      serverDate: Date.UTC(2026, 10, 10, 10, 0, 0),
      receivedAt: ARRIVED_AT,
    });
  });

  it("AW66: without a Date header the date is absent and the rest of the result is the same", async () => {
    fetchMock.mockResolvedValue(withDate(undefined));

    const result = await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });

    expect(result).toEqual({ kind: "ok", status: 200, data: { hello: "mundo" }, serverDate: undefined, receivedAt: ARRIVED_AT });
    expect(result).toHaveProperty("serverDate", undefined);
  });

  it.each([
    ["is not a date", "abc"],
    ["is empty", ""],
    ["names a day that does not exist", "Tue, 31 Feb 2026 10:00:00 GMT"],
    ["names an hour that does not exist", "Tue, 10 Nov 2026 25:00:00 GMT"],
    ["is a date but not an HTTP date", "2026-11-10T10:00:00Z"],
  ])("AW67: a Date header that %s gives an absent date, with no error and no log", async (_name, header) => {
    const logs = [vi.spyOn(console, "error"), vi.spyOn(console, "warn"), vi.spyOn(console, "log")];
    fetchMock.mockResolvedValue(withDate(header));

    const result = await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });

    expect(result).toMatchObject({ kind: "ok", status: 200, data: { hello: "mundo" } });
    expect(result.kind === "ok" && result.serverDate).toBeUndefined();
    expect(JSON.stringify(result)).not.toContain("NaN");
    for (const log of logs) expect(log).not.toHaveBeenCalled();
  });

  it("does not add the fields to a problem, to the network result or to an unexpected response", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    fetchMock.mockResolvedValueOnce(withDate(IMF_FIXDATE, { status: 403, headers: { "Content-Type": "application/problem+json" } }, JSON.stringify({ type: "https://confia.hn/problems/forbidden" })));
    fetchMock.mockResolvedValueOnce(withDate(IMF_FIXDATE, {}, "<html/>"));
    fetchMock.mockRejectedValueOnce(new TypeError("offline"));

    const problem = await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });
    const unexpected = await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });
    const network = await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });

    expect(problem).toEqual({ kind: "problem", problem: { status: 403, code: "forbidden", errors: [] } });
    expect(unexpected).toEqual({ kind: "unexpected-response", status: 200 });
    expect(network).toEqual({ kind: "network" });
  });
});

describe("a 2xx that does not meet the schema", () => {
  it.each([
    ["a body outside the schema", jsonResponse({ nope: true })],
    ["a body that is not JSON", new Response("<html/>", { status: 200 })],
    ["an empty body", new Response(null, { status: 200 })],
  ])("is an unexpected response for %s", async (_name, response) => {
    fetchMock.mockResolvedValue(response);

    expect(await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema })).toEqual({
      kind: "unexpected-response",
      status: 200,
    });
  });

  it("carries no part of the invalid body", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ hello: 1, accessToken: "SENTINEL-TOKEN" }));

    const result = await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });

    expect(JSON.stringify(result)).not.toContain("SENTINEL-TOKEN");
  });
});

describe("an error response", () => {
  it("parses a Problem into its known code", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ type: "https://confia.hn/problems/forbidden", traceId: "t-1" }, 403));

    expect(await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema })).toEqual({
      kind: "problem",
      problem: { status: 403, code: "forbidden", errors: [], traceId: "t-1" },
    });
  });

  it("AW43: a 502 with an HTML body is the unknown code with its status", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    fetchMock.mockResolvedValue(new Response("<html>Bad gateway</html>", { status: 502, headers: { "Content-Type": "text/html" } }));

    expect(await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema })).toEqual({
      kind: "problem",
      problem: { status: 502, code: "unknown", errors: [] },
    });
    expect(warn).toHaveBeenCalledExactlyOnceWith("Unknown problem response: status 502");
  });

  it("applies to requestEmpty too", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ type: "https://confia.hn/problems/csrf-invalid" }, 403));

    const result = await requestEmpty({ method: "DELETE", path: "/api/v1/sessions/current" });

    expect(result).toMatchObject({ kind: "problem", problem: { code: "csrf-invalid" } });
  });
});

describe("a failure without an answer", () => {
  it("AW43: a network error is the network result, without a status, and is not repeated", async () => {
    fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

    const result = await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });

    expect(result).toEqual({ kind: "network" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("is still the network result when fetch throws before returning a promise", async () => {
    fetchMock.mockImplementation(() => {
      throw new TypeError("blocked");
    });

    expect(await requestEmpty({ method: "DELETE", path: "/api/v1/sessions/current" })).toEqual({ kind: "network" });
  });

  it("is an unexpected response when the body of a 2xx cannot be read", async () => {
    const response = new Response("x", { status: 200 });
    vi.spyOn(response, "text").mockRejectedValue(new TypeError("stream failed"));
    fetchMock.mockResolvedValue(response);

    expect(await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema })).toEqual({
      kind: "unexpected-response",
      status: 200,
    });
  });
});

describe("the time limit", () => {
  function pendingUntilAbort(): void {
    fetchMock.mockImplementation(
      (_url, init) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => {
            reject(new DOMException("aborted", "AbortError"));
          });
        }),
    );
  }

  it("AW44: waits 31 seconds for a slow answer and processes it like any other", async () => {
    vi.useFakeTimers();
    let signal: AbortSignal | null | undefined;
    fetchMock.mockImplementation(
      (_url, init) =>
        new Promise<Response>((resolve) => {
          signal = init?.signal;
          setTimeout(() => {
            resolve(jsonResponse({ hello: "lento" }));
          }, 31_000);
        }),
    );

    const pending = request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });
    await vi.advanceTimersByTimeAsync(31_000);

    expect(await pending).toMatchObject({ kind: "ok", status: 200, data: { hello: "lento" } });
    expect(signal?.aborted).toBe(false);
  });

  it("gives up at 60 seconds with the network result, and not a moment before", async () => {
    vi.useFakeTimers();
    pendingUntilAbort();
    let settled = false;

    const pending = request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema }).then((result) => {
      settled = true;
      return result;
    });
    await vi.advanceTimersByTimeAsync(59_999);
    expect(settled).toBe(false);
    await vi.advanceTimersByTimeAsync(1);

    expect(await pending).toEqual({ kind: "network" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("does not repeat the request after the limit, because the server may have rotated", async () => {
    vi.useFakeTimers();
    pendingUntilAbort();

    const pending = requestEmpty({ method: "DELETE", path: "/api/v1/sessions/current" });
    await vi.advanceTimersByTimeAsync(600_000);

    expect(await pending).toEqual({ kind: "network" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("leaves no timer running once the request settles", async () => {
    vi.useFakeTimers();
    fetchMock.mockResolvedValue(jsonResponse({ hello: "x" }));

    await request({ method: "GET", path: "/api/v1/greeting", schema: greetingSchema });

    expect(vi.getTimerCount()).toBe(0);
  });
});

describe("the paths", () => {
  it.each(["https://evil.example/api/v1/x", "//evil.example/api/v1/x", "/other/v1/x", "api/v1/x", "/api", "/api/v1/x\\y", "/apix/v1", "/api/../admin", "/api/v1/../../x", "/api/./v1/x", "/api/%2e%2e/admin", "/api/v1/%2E./x", "/api/v1/.."])(
    "AW45: refuses %s before any request leaves",
    async (path) => {
      await expect(request({ method: "GET", path, schema: greetingSchema })).rejects.toThrow(TypeError);
      expect(fetchMock).not.toHaveBeenCalled();
    },
  );

  it("AW45: every URL it sends starts with /api/ and carries no scheme or host", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ hello: "x" }));

    await request({ method: "GET", path: "/api/v1/a?b=c", schema: greetingSchema });

    const [url] = fetchMock.mock.calls[0] ?? [];
    expect(url).toMatch(/^\/api\//);
    expect(url).not.toMatch(/^[a-z][a-z0-9+.-]*:|\/\//i);
  });
});

describe("the only fetch of the app (design.md D6)", () => {
  it("is called from http.ts and from no other production file of shared/api", () => {
    const dir = import.meta.dirname;
    const production = readdirSync(dir).filter((name) => name.endsWith(".ts") && !/\.test(-d)?\.ts$/.test(name));

    const callers = production.filter((name) => /\bfetch\s*\(/.test(readFileSync(join(dir, name), "utf8")));

    expect(production.length).toBeGreaterThan(1);
    expect(callers).toEqual(["http.ts"]);
  });
});
