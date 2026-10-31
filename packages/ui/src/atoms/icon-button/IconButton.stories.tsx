import type { Meta, StoryObj } from "@storybook/react-vite";
import { Plus } from "lucide-react";
import { IconButton } from "./IconButton";

const meta = {
  title: "Atoms/IconButton",
  component: IconButton,
  args: { label: "Agregar cargo", children: <Plus /> },
} satisfies Meta<typeof IconButton>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Secondary: Story = { args: { variant: "secondary" } };

export const Subtle: Story = { args: { variant: "subtle" } };

export const Destructive: Story = { args: { variant: "destructive" } };

export const Loading: Story = { args: { loading: true } };

export const Disabled: Story = { args: { disabled: true } };

export const Sizes: Story = {
  render: (args) => (
    <div className="flex items-center gap-4">
      <IconButton {...args} size="sm" />
      <IconButton {...args} size="md" />
      <IconButton {...args} size="lg" />
    </div>
  ),
};
