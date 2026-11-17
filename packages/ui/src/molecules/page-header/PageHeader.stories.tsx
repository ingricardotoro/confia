import type { Meta, StoryObj } from "@storybook/react-vite";
import { Badge } from "../../atoms/badge";
import { Button } from "../../atoms/button";
import { Breadcrumb } from "../breadcrumb";
import { PageHeader } from "./PageHeader";

const meta = {
  title: "Molecules/PageHeader",
  component: PageHeader,
  args: {
    title: "Listas de precios",
    description: "Precios por año lectivo y alcance, con su vigencia.",
    actions: <Button>Nueva lista</Button>,
  },
} satisfies Meta<typeof PageHeader>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Detail: Story = {
  args: {
    title: "Lista general 2027",
    breadcrumb: (
      <Breadcrumb
        label="Ruta de navegación"
        items={[
          { id: "lists", label: "Listas de precios", href: "/catalog/price-lists" },
          { id: "list", label: "Lista general 2027" },
        ]}
      />
    ),
    status: (
      <Badge variant="success" dot="filled">
        Activa
      </Badge>
    ),
    actions: (
      <>
        <Button variant="secondary">Archivar</Button>
        <Button>Agregar precio</Button>
      </>
    ),
  },
};
