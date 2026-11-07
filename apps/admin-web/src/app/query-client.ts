import { QueryClient } from "@tanstack/react-query";
import { isRetryableFailure } from "../shared/api/api-failure.ts";

const MAX_READ_RETRIES = 2;

/**
 * At most two retries of a read (AW23), and only of a failure that may pass: a network error or a 5xx
 * (`isRetryableFailure`). Never after a 4xx (design.md D9).
 */
export function retryRead(failureCount: number, error: unknown): boolean {
  return failureCount < MAX_READ_RETRIES && isRetryableFailure(error);
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
