import { QueryClient } from "@tanstack/react-query";

const MAX_READ_RETRIES = 2;

function statusOf(error: unknown): number | undefined {
  if (typeof error !== "object" || error === null || !("status" in error)) return undefined;
  return typeof error.status === "number" ? error.status : undefined;
}

/**
 * A read is worth repeating when the failure may pass: a network error or a 5xx. A 4xx is the server's
 * answer and repeating it only repeats the answer. An error without a status is a network failure.
 */
function isRetryable(error: unknown): boolean {
  const status = statusOf(error);
  return status === undefined || status >= 500;
}

/** At most two retries of a read (AW23), and none after a 4xx (design.md D9). */
export function retryRead(failureCount: number, error: unknown): boolean {
  return failureCount < MAX_READ_RETRIES && isRetryable(error);
}

/**
 * The app's single query client (design.md D9). `networkMode: "always"` keeps a request from pausing
 * silently when the browser reports no network, since the same-origin proxy can still answer. A
 * mutation never retries: the sign-in and sign-out operations are not safe to repeat blindly.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: retryRead,
        retryDelay: (attempt) => 1000 * 2 ** attempt,
        networkMode: "always",
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
      },
      mutations: { retry: 0, networkMode: "always" },
    },
  });
}
