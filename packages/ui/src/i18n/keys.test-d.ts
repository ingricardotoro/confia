import { describe, expectTypeOf, it } from "vitest";
import type { UiKey } from "./resources";
import type { useUiText } from "./use-ui-text";

// ADR-0011 static check: the catalog is typed, so a missing key or a built key is a tsc error.
// Each @ts-expect-error below is a permanent, deliberate violation; if the line stops failing,
// tsc reports an unused directive and this file turns red.
declare const t: ReturnType<typeof useUiText>["t"];
declare const suffix: string;

describe("typed catalog keys", () => {
  it("accepts a key that exists in the es-HN catalog", () => {
    expectTypeOf(t).parameter(0).toEqualTypeOf<UiKey>();
    t("common.status.paid");
  });

  it("BX06: rejects a key that is not in the catalog", () => {
    // @ts-expect-error ADR-0011: deliberate violation, the key is not in the es-HN catalog
    t("common.status.inexistente");
  });

  it("BX07: rejects a key built by concatenation", () => {
    // @ts-expect-error ADR-0011: deliberate violation, a key built by concatenation cannot be resolved
    t("common.status." + suffix);
  });
});
