import { focusRing } from "./focus-ring";

// The box that Checkbox and RadioGroupItem share (docs/ui-ux/02 section 6), so the two cannot drift
// apart, as field-state.ts does for the text fields. 24 pixels is the minimum target of WCAG 2.5.8
// (docs/ui-ux/03); the label next to it widens the click area. Checked fills come from the
// --choice-* tokens of components.css.
//
// `peer` is here because a checkbox or a radio usually comes BEFORE its label: that is the order in
// which Label's peer-disabled can dim it. In label-first order the owner passes `disabled` to Label.
//
// The transition lists its properties: transition-colors would also fade outline-color, and the
// focus ring would first show in the text color (see field-state.ts).
export const choiceControl = [
  "peer group inline-flex size-6 shrink-0 items-center justify-center border border-(--choice-border) bg-(--choice-bg)",
  "transition-[color,background-color,border-color] duration-(--duration-base) ease-standard",
  "disabled:cursor-not-allowed disabled:opacity-50",
  "aria-invalid:border-(--input-invalid-border)",
  focusRing,
].join(" ");
