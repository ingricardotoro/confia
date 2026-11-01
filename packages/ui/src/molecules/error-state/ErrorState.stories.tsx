import type { Meta, StoryObj } from "@storybook/react-vite";
import { ErrorState } from "./ErrorState";

const meta = {
  title: "Molecules/ErrorState",
  component: ErrorState,
  args: {
    title: "No pudimos cargar los cargos",
    description: "Revise su conexión e intente de nuevo.",
    onRetry: () => undefined,
  },
} satisfies Meta<typeof ErrorState>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithoutDescription: Story = {
  render: ({ title, onRetry }) => <ErrorState title={title} onRetry={onRetry} />,
};

export const CustomRetryLabel: Story = { args: { retryLabel: "Volver a cargar" } };
