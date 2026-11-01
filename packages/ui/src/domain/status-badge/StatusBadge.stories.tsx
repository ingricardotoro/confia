import type { Meta, StoryObj } from "@storybook/react-vite";
import { StatusBadge, type StatusBadgeProps } from "./StatusBadge";
import { FINANCIAL_STATUSES, type FinancialStatus } from "./status-table";

const meta = {
  title: "Domain/StatusBadge",
  component: StatusBadge,
  args: { status: "paid" },
} satisfies Meta<typeof StatusBadge>;

export default meta;

type Story = StoryObj<typeof meta>;

// One story per variant lists the seven states, so the gate checks 7 x 3 in both themes.
function allStates(props: Omit<StatusBadgeProps, "status">) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      {FINANCIAL_STATUSES.map((status) => (
        <StatusBadge key={status} {...props} status={status} />
      ))}
    </div>
  );
}

export const Default: Story = {};

export const Soft: Story = { render: (args) => allStates({ ...args, variant: "soft" }) };

export const Solid: Story = { render: (args) => allStates({ ...args, variant: "solid" }) };

export const Outline: Story = { render: (args) => allStates({ ...args, variant: "outline" }) };

export const IconOnly: Story = { render: (args) => allStates({ ...args, iconOnly: true }) };

export const Small: Story = { render: (args) => allStates({ ...args, size: "sm" }) };

// A value outside the seven states, as a server that adds one before the front end does.
export const Unknown: Story = { args: { status: "archived" as unknown as FinancialStatus } };
