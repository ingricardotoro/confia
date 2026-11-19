import type { Page } from "@playwright/test";

/**
 * Clicks an entry of the main navigation. The groups of the sidebar start collapsed (only the group of the current route is
 * open), so when the link is not visible the collapsed groups are opened first, as a person would do with one click.
 */
export async function clickNavigationLink(page: Page, name: string): Promise<void> {
  const navigation = page.getByRole("navigation", { name: "Navegación principal" });
  // Wait for the navigation to be drawn: "Inicio" is a plain link, visible whatever the groups do.
  await navigation.getByRole("link", { name: "Inicio" }).waitFor();
  const link = navigation.getByRole("link", { name });
  if (!(await link.isVisible())) {
    const collapsed = navigation.locator('button[aria-expanded="false"]');
    for (let remaining = await collapsed.count(); remaining > 0 && !(await link.isVisible()); remaining -= 1) {
      await collapsed.first().click();
    }
  }
  await link.click();
}
