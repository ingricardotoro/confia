import { describe, expectTypeOf, it } from "vitest";
import type { AdminKey } from "./resources.ts";
import type { useAdminText } from "./use-admin-text.ts";

// ADR-0011 static check (AW26): the catalog is typed, so a missing key or a built key is a tsc error.
// Each @ts-expect-error below is a permanent, deliberate violation; if the line stops failing,
// tsc reports an unused directive and this file turns red.
declare const t: ReturnType<typeof useAdminText>["t"];
declare const suffix: string;

describe("typed admin catalog keys", () => {
  it("accepts a key that exists in the es-HN admin catalog", () => {
    expectTypeOf(t).parameter(0).toEqualTypeOf<AdminKey>();
    t("signIn.contact");
  });

  it("AW26: rejects a key that is not in the catalog", () => {
    // @ts-expect-error ADR-0011: deliberate violation, the key is not in the es-HN admin catalog
    t("signIn.inexistente");
  });

  it("rejects a key built by concatenation", () => {
    // @ts-expect-error ADR-0011: deliberate violation, a key built by concatenation cannot be resolved
    t("signIn." + suffix);
  });
});
