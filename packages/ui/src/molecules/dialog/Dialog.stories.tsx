import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { Button } from "../../atoms/button";
import { Input } from "../../atoms/input";
import { FormErrorSummary } from "../form-error-summary";
import { FormField } from "../form-field";
import { Dialog, type DialogProps } from "./Dialog";

// The dialog mounts inside the story's own canvas, so the accessibility gate, which scans the canvas,
// evaluates it. Text here is example data, not interface copy.
function Example(args: DialogProps) {
  const [container, setContainer] = useState<HTMLElement | null>(null);
  return (
    <div ref={setContainer}>
      {container === null ? null : <Dialog {...args} container={container} />}
    </div>
  );
}

const meta: Meta<DialogProps> = {
  title: "Molecules/Dialog",
  component: Dialog,
  render: (args) => <Example {...args} />,
  args: {
    open: true,
    onOpenChange: () => undefined,
    title: "Nueva modalidad",
    description: "Registre el código y el nombre con que la institución la identifica.",
    children: <FormField label="Nombre">{(control) => <Input {...control} />}</FormField>,
    footer: (
      <>
        <Button variant="secondary">Cancelar</Button>
        <Button>Guardar</Button>
      </>
    ),
  },
};

export default meta;

type Story = StoryObj<DialogProps>;

export const Default: Story = {};

export const WithoutFooter: Story = { args: { footer: undefined } };

const TALL_FIELDS = Array.from({ length: 10 }, (_, index) => `campo-${String(index + 1)}`);
const TALL_ERRORS = TALL_FIELDS.slice(0, 3).map((controlId, index) => ({
  controlId,
  message: `Corrija el campo ${String(index + 1)}.`,
}));

// A form taller than the dialog allows, with the error summary on top. The body scrolls and the title and the
// footer stay in view; the layout test (Dialog.layout.a11y.test.tsx) measures it at 1280 x 720.
export const TallFormWithErrorSummary: Story = {
  args: {
    children: (
      <form id="tall-form" noValidate className="flex flex-col gap-4">
        <FormErrorSummary errors={TALL_ERRORS} />
        {TALL_FIELDS.map((controlId, index) => (
          <FormField
            key={controlId}
            label={`Campo ${String(index + 1)}`}
            controlId={controlId}
            {...(index < TALL_ERRORS.length ? { error: `Corrija el campo ${String(index + 1)}.` } : {})}
          >
            {(control) => <Input {...control} />}
          </FormField>
        ))}
      </form>
    ),
  },
};
