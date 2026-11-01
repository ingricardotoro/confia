import { describe, expectTypeOf, it } from "vitest";
import type { ErrorStateProps } from "./ErrorState";

// ADR-0018 static check (DS27): an error state without a way to retry must not compile. Each
// `@ts-expect-error` marks a permanent, deliberate violation; if the line stops failing, tsc reports an
// unused directive and this file turns red.
describe("ErrorState props", () => {
  it("requires the retry handler", () => {
    // @ts-expect-error ADR-0018: deliberate violation, the onRetry prop is required
    const withoutRetry: ErrorStateProps = { title: "No pudimos cargar" };

    const withRetry: ErrorStateProps = { title: "No pudimos cargar", onRetry: () => undefined };
    expectTypeOf(withRetry.onRetry).toEqualTypeOf<() => void>();
    expectTypeOf(withoutRetry).toExtend<ErrorStateProps>();
  });

  it("keeps the label of the retry optional, so the catalog supplies it", () => {
    expectTypeOf<ErrorStateProps["retryLabel"]>().toEqualTypeOf<string | undefined>();
  });
});
