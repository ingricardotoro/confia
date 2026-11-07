import { afterEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../app/query-client.ts";
import { ApiFailure } from "../shared/api/api-failure.ts";
import { endCurrentSession, getCurrentSession } from "../shared/api/auth-operations.ts";
import { problem } from "./contract-responses.ts";
import { installMswServer, requests, route } from "./msw-server.ts";

// admin-web-foundations-and-session, task 5.1 (AW20 to AW22, design.md D6 and D9). The app's query client
// meets the real operations over the test server: a write is never repeated, a read repeats only what may
// pass. It sits in src/test, not in shared/api, because shared/ may not import the app (the dependency
// rule admin-shared-never-imports-features). Timers are simulated: no test waits one real second.
installMswServer();

afterEach(() => {
  vi.useRealTimers();
});

const CURRENT = "/api/v1/auth/sessions/current";

describe("the query client with the access operations", () => {
  it("AW20: a mutation that fails by network makes exactly one request", async () => {
    const client = createQueryClient();
    route("delete", CURRENT, "network-error");
    const mutationFn = async (): Promise<void> => {
      const result = await endCurrentSession("csrf-A");
      if (result.kind !== "ok") throw new ApiFailure(result);
    };

    await expect(client.getMutationCache().build(client, { mutationFn }).execute(undefined)).rejects.toBeInstanceOf(ApiFailure);

    expect(requests()).toHaveLength(1);
  });

  async function readCurrent(client: ReturnType<typeof createQueryClient>, key: string): Promise<void> {
    await client.query({
      queryKey: ["probe", key],
      queryFn: async () => {
        const result = await getCurrentSession(`tok-${crypto.randomUUID()}`);
        if (result.kind !== "ok") throw new ApiFailure(result);
        return result.data;
      },
    });
  }

  it("AW21: a read answered with 503 makes three requests, one second and then two seconds apart", async () => {
    vi.useFakeTimers();
    route("get", CURRENT, problem("capacity-exceeded", 503));
    const settled = readCurrent(createQueryClient(), "503").then(
      () => "resolved",
      () => "rejected",
    );

    await vi.advanceTimersByTimeAsync(10_000);

    await expect(settled).resolves.toBe("rejected");
    const [first, second, third, ...rest] = requests().map((sent) => sent.at);
    expect(rest).toEqual([]);
    expect(first).toEqual(expect.any(Number));
    expect([(second ?? NaN) - (first ?? NaN), (third ?? NaN) - (second ?? NaN)]).toEqual([1000, 2000]);
  });

  it.each([
    [401, problem("authentication-required", 401)],
    [404, problem("resource-not-found", 404)],
  ] as const)("AW22: a read answered with %i makes exactly one request", async (_status, reply) => {
    vi.useFakeTimers();
    route("get", CURRENT, reply);
    const settled = readCurrent(createQueryClient(), String(_status)).then(
      () => "resolved",
      () => "rejected",
    );

    await vi.advanceTimersByTimeAsync(10_000);

    await expect(settled).resolves.toBe("rejected");
    expect(requests()).toHaveLength(1);
  });
});
