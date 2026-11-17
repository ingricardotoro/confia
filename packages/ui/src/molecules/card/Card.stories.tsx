import type { Meta, StoryObj } from "@storybook/react-vite";
import { Card } from "./Card";

const meta = {
  title: "Molecules/Card",
  component: Card,
  args: { children: <p className="text-sm text-text-primary">Contenido de la tarjeta.</p> },
} satisfies Meta<typeof Card>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithTitle: Story = { args: { title: "Resumen", description: "Datos de la lista de precios." } };

export const LargePadding: Story = { args: { padding: "lg" } };
