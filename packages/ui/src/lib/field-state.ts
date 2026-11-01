import { focusRing } from "./focus-ring";

// The states of a text field (docs/ui-ux/02 section 6), shared by Input and the Textarea of 5.1 so
// the two cannot drift apart. Colors come from the --input-* tokens of components.css.
//
// Read-only and disabled are deliberately different: a read-only field keeps the pointer and the
// focus, so an auditor can select and copy the value, and only changes its surface. A disabled one
// loses the cursor and half its opacity, and the browser takes it out of the tab order. The
// :read-only pseudo-class also matches a disabled input, hence read-only:not-disabled: on every
// read-only utility.
export const fieldState = [
  "w-full rounded-md border border-(--input-border) bg-(--input-bg) text-(--input-fg)",
  "placeholder:text-(--input-placeholder)",
  "transition-colors duration-(--duration-base) ease-standard",
  "read-only:not-disabled:bg-bg-canvas read-only:not-disabled:text-text-secondary",
  "disabled:cursor-not-allowed disabled:opacity-50",
  "aria-invalid:border-(--input-invalid-border)",
  focusRing,
].join(" ");
