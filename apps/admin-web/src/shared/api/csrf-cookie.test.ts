import { afterEach, describe, expect, it, vi } from "vitest";
import { CSRF_COOKIE_NAME, readCsrfToken } from "./csrf-cookie.ts";

// admin-web-foundations-and-session, task 4.1 (AW40, design.md D6). The cookie is matched by its exact
// name: a longer name that merely contains it is another cookie and must not be taken for it.
afterEach(() => {
  vi.restoreAllMocks();
});

describe("readCsrfToken", () => {
  it("AW40: finds no token when only a similarly named cookie exists", () => {
    expect(readCsrfToken("x__Host-confia_csrf=falso")).toBeUndefined();
    expect(readCsrfToken("__Host-confia_csrf_extra=falso; other=1")).toBeUndefined();
    expect(readCsrfToken("prefix; __Host-confia_csrf2=falso")).toBeUndefined();
  });

  it("reads the value of the cookie with the exact name, wherever it sits in the header", () => {
    expect(readCsrfToken(`${CSRF_COOKIE_NAME}=alone`)).toBe("alone");
    expect(readCsrfToken(`a=1; ${CSRF_COOKIE_NAME}=middle; b=2`)).toBe("middle");
    expect(readCsrfToken(`a=1; ${CSRF_COOKIE_NAME}=last`)).toBe("last");
  });

  it("keeps an equals sign inside the value", () => {
    expect(readCsrfToken(`${CSRF_COOKIE_NAME}=ab==`)).toBe("ab==");
  });

  it("treats an empty value and an empty header as an absent cookie (SS08)", () => {
    expect(readCsrfToken(`${CSRF_COOKIE_NAME}=`)).toBeUndefined();
    expect(readCsrfToken("")).toBeUndefined();
  });

  it("uses the exact name the server sets", () => {
    expect(CSRF_COOKIE_NAME).toBe("__Host-confia_csrf");
  });

  it("reads document.cookie when it is given no header", () => {
    vi.spyOn(document, "cookie", "get").mockReturnValue(`a=1; ${CSRF_COOKIE_NAME}=from-document`);

    expect(readCsrfToken()).toBe("from-document");
  });
});
