import { composeStories } from "@storybook/react-vite";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Skeleton } from "./Skeleton";
import * as stories from "./Skeleton.stories";

describe("Skeleton", () => {
  it("is hidden from assistive technology: it carries no content to announce", () => {
    const { container } = renderWithI18n(<Skeleton className="h-4 w-32" />);

    expect(container.firstElementChild).toHaveAttribute("aria-hidden", "true");
  });

  it("takes its shape from the class of the caller, so it can mirror the real content", () => {
    const { container } = renderWithI18n(<Skeleton className="h-4 w-32 rounded-full" />);

    expect(container.firstElementChild).toHaveClass("h-4", "w-32", "rounded-full");
  });

  it("reads its color and its animation from component tokens, which reduced motion shortens", () => {
    const { container } = renderWithI18n(<Skeleton />);

    expect(container.firstElementChild).toHaveClass("bg-(--skeleton-bg)", "animate-(--skeleton-animation)");
  });

  it("DS63: the Loading story is its loading state, made of hidden blocks and no spinner", () => {
    const { Loading } = composeStories(stories);
    const { container } = renderWithI18n(<Loading />);

    expect(container.querySelectorAll('[aria-hidden="true"]').length).toBeGreaterThan(1);
    expect(container.querySelector('[role="status"]')).toBeNull();
  });
});
