import type { Meta, StoryObj } from "@storybook/react-vite";
import { Label } from "./Label";

const meta = {
  title: "Atoms/Label",
  component: Label,
  args: { htmlFor: "story-amount", children: "Monto a cobrar" },
} satisfies Meta<typeof Label>;

export default meta;

type Story = StoryObj<typeof meta>;

// The control is a native input on purpose: the Input atom lands in 4.2 and a Label story must
// not depend on a later task. The peer class is what lets the Label dim itself next to a disabled
// control.
export const Default: Story = {
  render: (args) => (
    <div className="flex flex-col gap-2">
      <Label {...args} />
      <input id="story-amount" className="peer h-10 rounded-md border border-border-interactive bg-bg-surface px-3 text-text-primary" />
    </div>
  ),
};

export const WithDisabledControl: Story = {
  render: (args) => (
    <div className="flex flex-col gap-2">
      <input id="story-amount" disabled className="peer order-2 h-10 rounded-md border border-border-interactive bg-bg-surface px-3 text-text-primary" />
      <Label {...args} className="order-1" />
    </div>
  ),
};
