import type { Meta, StoryObj } from "@storybook/react-vite";

// A single violation of moderate impact (DS69, BX16): form-field-multiple-labels. It is reported in
// reports/a11y/report.json and must not fail the gate.
const meta = { title: "Fixtures/ModerateOnly" } satisfies Meta;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {
  render: () => (
    <div className="bg-bg-surface p-2 text-text-primary">
      <label htmlFor="fixture-field">Nombre</label>
      <label htmlFor="fixture-field">Nombre completo</label>
      <input id="fixture-field" type="text" />
    </div>
  ),
};
