import { renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { AuthorizedSendProvider, useAuthorizedSend, type AuthorizedSend } from "./authorized-send.tsx";

// The port a screen uses to send an authenticated request: it is given by the signed in shell, and a screen
// outside the shell is a defect that fails at once, not a request with no token.
describe("useAuthorizedSend", () => {
  it("returns the sender the provider holds", () => {
    const send = vi.fn() as unknown as AuthorizedSend;
    const wrapper = ({ children }: { children: ReactNode }) => <AuthorizedSendProvider send={send}>{children}</AuthorizedSendProvider>;

    const { result } = renderHook(() => useAuthorizedSend(), { wrapper });

    expect(result.current).toBe(send);
  });

  it("throws outside a provider", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);

    expect(() => renderHook(() => useAuthorizedSend())).toThrow("AuthorizedSendProvider");
  });
});
