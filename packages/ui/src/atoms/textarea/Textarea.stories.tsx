import type { Meta, StoryObj } from "@storybook/react-vite";
import { Textarea } from "./Textarea";

const meta = {
  title: "Atoms/Textarea",
  component: Textarea,
  args: { "aria-label": "Observaciones", defaultValue: "Pago parcial acordado con el encargado." },
} satisfies Meta<typeof Textarea>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const ReadOnly: Story = { args: { readOnly: true } };

export const Disabled: Story = { args: { disabled: true } };

export const Invalid: Story = { args: { "aria-invalid": true } };
