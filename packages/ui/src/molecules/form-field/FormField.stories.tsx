import type { Meta, StoryObj } from "@storybook/react-vite";
import { Checkbox } from "../../atoms/checkbox";
import { Input } from "../../atoms/input";
import { RadioGroup, RadioGroupItem } from "../../atoms/radio-group";
import { Textarea } from "../../atoms/textarea";
import { FormField } from "./FormField";

const meta = {
  title: "Molecules/FormField",
  component: FormField,
  args: {
    label: "Nombre del encargado",
    children: (control) => <Input {...control} />,
  },
} satisfies Meta<typeof FormField>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithDescription: Story = { args: { description: "Tal como aparece en su documento de identidad." } };

export const WithError: Story = {
  args: {
    description: "Tal como aparece en su documento de identidad.",
    error: "Ingrese el nombre del encargado.",
  },
};

export const Required: Story = { args: { required: true } };

export const Disabled: Story = { args: { disabled: true } };

export const WithTextarea: Story = {
  args: { label: "Observaciones", children: (control) => <Textarea {...control} /> },
};

export const WithCheckbox: Story = {
  args: {
    label: "Enviar recibo por correo",
    error: "Confirme el envío del recibo.",
    children: (control) => <Checkbox {...control} />,
  },
};

export const WithRadioGroup: Story = {
  args: {
    label: "Método de pago",
    required: true,
    children: (control) => (
      <RadioGroup {...control}>
        <RadioGroupItem value="cash" aria-label="Efectivo" />
        <RadioGroupItem value="card" aria-label="Tarjeta" />
      </RadioGroup>
    ),
  },
};
