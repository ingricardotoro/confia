import type { Meta, StoryObj } from "@storybook/react-vite";
import { Input } from "../../atoms/input";
import { FormField } from "../form-field";
import { FormErrorSummary } from "./FormErrorSummary";

// The controls are part of each story: a link exists only when its control is in the document.
const meta = {
  title: "Molecules/FormErrorSummary",
  component: FormErrorSummary,
  args: {
    errors: [{ controlId: "identifier", message: "Ingrese el identificador." }],
  },
  render: ({ errors }) => (
    <div className="flex max-w-md flex-col gap-4">
      <FormErrorSummary errors={errors} />
      {errors.map(({ controlId, message }) => (
        <FormField key={controlId} label={`Campo ${controlId}`} controlId={controlId} error={message}>
          {(control) => <Input {...control} />}
        </FormField>
      ))}
    </div>
  ),
} satisfies Meta<typeof FormErrorSummary>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Several: Story = {
  args: {
    errors: [
      { controlId: "identifier", message: "Ingrese el identificador." },
      { controlId: "code", message: "Ingrese el código de verificación." },
      { controlId: "method", message: "Seleccione un método." },
    ],
  },
};

export const WithoutControl: Story = {
  render: ({ errors }) => (
    <div className="flex max-w-md flex-col gap-4">
      <FormErrorSummary errors={errors} />
    </div>
  ),
  args: {
    errors: [{ controlId: "not-in-the-document", message: "El servidor rechazó la solicitud." }],
  },
};
