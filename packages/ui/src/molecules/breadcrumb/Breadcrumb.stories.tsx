import type { Meta, StoryObj } from "@storybook/react-vite";
import { Breadcrumb } from "./Breadcrumb";

const meta = {
  title: "Molecules/Breadcrumb",
  component: Breadcrumb,
  args: {
    label: "Ruta de navegación",
    items: [
      { id: "catalog", label: "Catálogo", href: "/catalog" },
      { id: "lists", label: "Listas de precios", href: "/catalog/price-lists" },
      { id: "list", label: "Lista general 2027" },
    ],
  },
} satisfies Meta<typeof Breadcrumb>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const SingleLevel: Story = { args: { items: [{ id: "list", label: "Lista general 2027" }] } };
