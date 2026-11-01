import type { Meta, StoryObj } from "@storybook/react-vite";
import { EmptyState } from "./EmptyState";

const meta = {
  title: "Molecules/EmptyState",
  component: EmptyState,
  args: {
    title: "Sin cargos en el período",
    description: "Cuando se genere un cargo para este estudiante aparecerá aquí.",
    action: { label: "Agregar cargo", onAction: () => undefined },
  },
} satisfies Meta<typeof EmptyState>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithoutDescription: Story = {
  render: ({ title, action }) => <EmptyState title={title} action={action} />,
};
