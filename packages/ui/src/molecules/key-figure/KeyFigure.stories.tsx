import type { Meta, StoryObj } from "@storybook/react-vite";
import { KeyFigure } from "./KeyFigure";

const meta = {
  title: "Molecules/KeyFigure",
  component: KeyFigure,
  args: { label: "Listas en total", value: "24" },
} satisfies Meta<typeof KeyFigure>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Money: Story = { args: { label: "Total del día", value: "L 3,200.00" } };

export const Loading: Story = { args: { loading: true } };
