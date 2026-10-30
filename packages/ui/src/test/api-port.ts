/** Vitest's own default (63315) falls in a range Windows reserves on some machines: listen EACCES. */
export const DEFAULT_API_PORT = 51730;

/**
 * Reads CONFIA_VITEST_API_PORT. Unset means the default; anything set that is not a whole number
 * from 1 to 65535 throws, because Vitest ignores `port: 0` and NaN and would silently fall back to
 * the very port this setting exists to avoid.
 */
export function parseApiPort(raw: string | undefined): number {
  if (raw === undefined) return DEFAULT_API_PORT;
  const port = /^[0-9]+$/.test(raw) ? Number(raw) : Number.NaN;
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`CONFIA_VITEST_API_PORT must be a whole number from 1 to 65535, got ${JSON.stringify(raw)}.`);
  }
  return port;
}
