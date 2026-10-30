import { clsx, type ClassValue } from "clsx";
import { extendTailwindMerge } from "tailwind-merge";

// tailwind-merge only knows the scales of Tailwind's default theme, and CONFIA replaces that theme
// (docs/ui-ux/01 section 14). Font weights and easing curves carry CONFIA's own names, so without
// them "font-regular font-bold" would be read as a font family plus a weight and both would stay.
// Colors, font sizes, spacing and radii keep their default names and need no declaration.
const merge = extendTailwindMerge({
  extend: {
    theme: {
      "font-weight": ["regular", "medium", "semibold", "bold"],
      ease: ["standard", "decelerate", "accelerate"],
    },
  },
});

/** Joins class names like clsx and resolves conflicting Tailwind utilities, the last one winning. */
export function cn(...inputs: ClassValue[]): string {
  return merge(clsx(inputs));
}
