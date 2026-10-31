import type { Meta, StoryObj } from "@storybook/react-vite";
import { Separator } from "./Separator";

const meta = {
  title: "Atoms/Separator",
  component: Separator,
} satisfies Meta<typeof Separator>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {
  render: (args) => (
    <div className="w-64">
      <Separator {...args} />
    </div>
  ),
};

export const Semantic: Story = { ...Default, args: { decorative: false } };

export const Vertical: Story = {
  render: (args) => (
    <div className="h-8">
      <Separator {...args} />
    </div>
  ),
  args: { orientation: "vertical" },
};
