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

    expect(result).toEqual({ kind: "ok", status: 200, data: { hello: "mundo" } });
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

    expect(await requestEmpty({ method: "DELETE", path: "/api/v1/sessions/current" })).toEqual({
      kind: "ok",
      status: 204,
      data: undefined,
    });
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

    expect(await pending).toEqual({ kind: "ok", status: 200, data: { hello: "lento" } });
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
  it.each(["https://evil.example/api/v1/x", "//evil.example/api/v1/x", "/other/v1/x", "api/v1/x", "/api", "/api/v1/x\\y", "/apix/v1"])(
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
