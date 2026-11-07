/** The cookie the server sets for the double-submit CSRF check (SessionCookies.java). */
export const CSRF_COOKIE_NAME = "__Host-confia_csrf";

/**
 * Reads the CSRF token from a cookie header, matching the name exactly: `x__Host-confia_csrf` is another
 * cookie (AW40). An empty value is an absent cookie (SS08). This is the only read of `document.cookie`
 * in the app; the server owns every cookie and the app never writes one.
 */
export function readCsrfToken(cookieHeader: string = document.cookie): string | undefined {
  for (const pair of cookieHeader.split("; ")) {
    const separator = pair.indexOf("=");
    if (separator === -1 || pair.slice(0, separator) !== CSRF_COOKIE_NAME) continue;
    const value = pair.slice(separator + 1);
    return value === "" ? undefined : value;
  }
  return undefined;
}
