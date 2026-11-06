import { useQueryClient } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useAdminText } from "../i18n/use-admin-text.ts";
import { createAdminI18n } from "../i18n/create-admin-i18n.ts";
import { AppProviders } from "./AppProviders.tsx";
import { createQueryClient } from "./query-client.ts";

// admin-web-foundations-and-session, task 3.1 (AW23, AW24): the providers are what hands every
// descendant the same query client and the same catalog.
function Probe({ onClient }: { readonly onClient: (client: ReturnType<typeof useQueryClient>) => void }) {
  const { t } = useAdminText();
  onClient(useQueryClient());
  return <p>{t("boot.restoring")}</p>;
}

describe("the app providers", () => {
  it("give descendants the given query client and the admin catalog", () => {
    const queryClient = createQueryClient();
    const seen: unknown[] = [];

    render(
      <AppProviders queryClient={queryClient} i18n={createAdminI18n()}>
        <Probe onClient={(client) => seen.push(client)} />
      </AppProviders>,
    );

    expect(screen.getByText("Restaurando su sesión.")).toBeInTheDocument();
    expect(seen.length).toBeGreaterThan(0);
    expect(seen.every((client) => client === queryClient)).toBe(true);
  });
});
