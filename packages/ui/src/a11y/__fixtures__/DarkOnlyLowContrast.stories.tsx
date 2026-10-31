import type { Meta, StoryObj } from "@storybook/react-vite";

// Sufficient contrast in the light theme, none in the dark one: the semantic text token is redefined
// for the fixture alone, and only under data-theme="dark" (DS68). A gate that checks one theme passes it.
const meta = { title: "Fixtures/DarkOnlyLowContrast" } satisfies Meta;

export default meta;

type Story = StoryObj<typeof meta>;

const darkOverride = `:root[data-theme="dark"] [data-fixture="dark-only"] { --color-text-primary: var(--color-bg-surface); }`;

export const Default: Story = {
  render: () => (
    <>
      <style>{darkOverride}</style>
      <p data-fixture="dark-only" className="bg-bg-surface p-2 text-text-primary">
        Texto legible solo en el tema claro
      </p>
    </>
  ),
};
