import type { Meta, StoryObj } from "@storybook/react-vite";
import { Select } from "./Select";

// Option text is example data, not interface copy.
const meta = {
  title: "Atoms/Select",
  component: Select,
  args: { "aria-label": "Modalidad", defaultValue: "" },
  render: (args) => (
    <Select {...args}>
      <option value="">Seleccione una modalidad</option>
      <option value="regular">Educación regular</option>
      <option value="night">Educación nocturna</option>
    </Select>
  ),
} satisfies Meta<typeof Select>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Invalid: Story = { args: { "aria-invalid": true } };

export const Disabled: Story = { args: { disabled: true } };
