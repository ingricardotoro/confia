import type { Meta, StoryObj } from "@storybook/react-vite";
import { Label } from "../label";
import { RadioGroup, RadioGroupItem, type RadioGroupProps } from "./RadioGroup";

interface StoryArgs extends RadioGroupProps {
  /** Marks every option invalid, as FormField will when the group has an error. */
  invalid?: boolean;
}

// Each option is a control followed by its label, the order that lets the label dim next to a
// disabled control. Option text is example data, not interface copy.
const OPTIONS = [
  { value: "cash", text: "Efectivo" },
  { value: "card", text: "Tarjeta" },
  { value: "transfer", text: "Transferencia" },
] as const;

const meta: Meta<StoryArgs> = {
  title: "Atoms/RadioGroup",
  component: RadioGroup,
  args: { "aria-label": "Método de pago", defaultValue: "card" },
  render: ({ invalid, ...args }) => (
    <RadioGroup {...args}>
      {OPTIONS.map(({ value, text }) => (
        <div key={value} className="flex items-center gap-2">
          <RadioGroupItem id={`story-${value}`} value={value} aria-invalid={invalid} />
          <Label htmlFor={`story-${value}`}>{text}</Label>
        </div>
      ))}
    </RadioGroup>
  ),
};

export default meta;

type Story = StoryObj<StoryArgs>;

export const Default: Story = {};

export const Disabled: Story = { args: { disabled: true } };

export const Invalid: Story = { args: { invalid: true } };
