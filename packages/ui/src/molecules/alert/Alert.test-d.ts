import { describe, expectTypeOf, it } from "vitest";
import type { AlertProps } from "./Alert";

// ADR-0018 static check (DS85): an alert with no content must not compile. Each `@ts-expect-error`
// marks a permanent, deliberate violation; if the line stops failing, tsc reports an unused directive
// and this file turns red.
describe("Alert props", () => {
  it("requires the content", () => {
    // @ts-expect-error ADR-0018: deliberate violation, the children prop is required
    const withoutContent: AlertProps = { variant: "info" };

    const withContent: AlertProps = { children: "Contenido" };
    expectTypeOf(withContent.variant).toEqualTypeOf<"info" | "warning" | "destructive" | null | undefined>();
    expectTypeOf(withoutContent).toExtend<AlertProps>();
  });

  it("keeps the title optional", () => {
    expectTypeOf<AlertProps["title"]>().toEqualTypeOf<string | undefined>();
  });
});
