import type { Meta, StoryObj } from "@storybook/react-vite";

// Permanent deliberate violation (design.md decision 8). It lives outside the discovery patterns of
// both Storybook and the real-story gate, so it never reaches the published catalog; gate.a11y.test.tsx
// feeds it to the same function as the real stories and requires every story here to be rejected.
const meta = { title: "Fixtures/Inaccessible" } satisfies Meta;

export default meta;

type Story = StoryObj<typeof meta>;

/** image-alt: an image with no alternative text. */
export const MissingImageAlt: Story = {
  render: () => (
    <img src="data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==" width="24" height="24" />
  ),
};

/** button-name: a button with no accessible name. */
export const UnnamedButton: Story = {
  render: () => <button type="button" className="size-8 bg-primary" />,
};

/** color-contrast: text a few shades away from its background, in the light theme. */
export const LowContrastText: Story = {
  render: () => <p className="bg-bg-surface p-2 text-text-disabled">Texto con contraste insuficiente</p>,
};
