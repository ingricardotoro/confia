// The one focus indicator of the design system (docs/ui-ux/02 section 6, docs/ui-ux/03 2.4.7): a two
// pixel outline in --color-border-focus, drawn only for keyboard focus. Every interactive atom
// spreads this constant instead of writing its own, so the rule "never outline-none without a
// replacement" has a single place to hold.
export const focusRing =
  "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus";
