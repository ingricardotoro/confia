import type { Meta, StoryObj } from "@storybook/react-vite";
import { Alert } from "./Alert";

const meta = {
  title: "Molecules/Alert",
  component: Alert,
  args: {
    title: "Mantenimiento programado",
    children: "El sistema estará en mantenimiento el sábado a las 22:00.",
  },
} satisfies Meta<typeof Alert>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Info: Story = { args: { variant: "info" } };

export const Warning: Story = {
  args: { variant: "warning", title: "La sesión vence pronto", children: "Guarde el trabajo en curso." },
};

export const Destructive: Story = {
  args: { variant: "destructive", title: "No se pudo guardar", children: "Intente de nuevo en unos minutos." },
};

export const WithoutTitle: Story = {
  render: ({ children }) => <Alert>{children}</Alert>,
};
