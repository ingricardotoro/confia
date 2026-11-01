import type { Meta, StoryObj } from "@storybook/react-vite";
import { Info } from "lucide-react";
import { Badge } from "./Badge";

const meta = {
  title: "Atoms/Badge",
  component: Badge,
  args: { children: "Etiqueta" },
} satisfies Meta<typeof Badge>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Neutral: Story = { args: { variant: "neutral" } };

export const Primary: Story = { args: { variant: "primary" } };

export const Outline: Story = { args: { variant: "outline" } };

export const WithIcon: Story = { args: { icon: <Info /> } };

export const Sizes: Story = {
  render: (args) => (
    <div className="flex items-center gap-4">
      <Badge {...args} size="sm" />
      <Badge {...args} size="md" />
    </div>
  ),
};
