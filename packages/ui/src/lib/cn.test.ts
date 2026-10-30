import { twMerge } from "tailwind-merge";
import { describe, expect, it } from "vitest";
import { cn } from "./cn";

describe("cn", () => {
  it("keeps a font size and a semantic text color together", () => {
    expect(cn("text-sm", "text-status-paid-fg")).toBe("text-sm text-status-paid-fg");
  });

  it("lets the last class of the same group win", () => {
    expect(cn("p-2", "p-4")).toBe("p-4");
    expect(cn("text-sm", "text-lg")).toBe("text-lg");
    expect(cn("text-status-paid-fg", "text-primary-fg")).toBe("text-primary-fg");
  });

  it("resolves the font weights of the CONFIA scale, which tailwind-merge does not know", () => {
    expect(twMerge("font-regular font-bold")).toBe("font-regular font-bold");
    expect(cn("font-regular", "font-bold")).toBe("font-bold");
    expect(cn("font-medium", "font-regular")).toBe("font-regular");
  });

  it("resolves the easing curves of the CONFIA scale", () => {
    expect(twMerge("ease-standard ease-accelerate")).toBe("ease-standard ease-accelerate");
    expect(cn("ease-standard", "ease-accelerate")).toBe("ease-accelerate");
  });

  it("accepts conditionals, arrays and objects like clsx", () => {
    const hidden = false as boolean;
    expect(cn("a", hidden && "b", undefined, null, ["c", { d: true, e: false }])).toBe("a c d");
  });
});
