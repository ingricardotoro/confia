import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { Button } from "../../atoms/button";
import { Input } from "../../atoms/input";
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
