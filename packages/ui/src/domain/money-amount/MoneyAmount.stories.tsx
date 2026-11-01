import type { Meta, StoryObj } from "@storybook/react-vite";
import { MoneyAmount } from "./MoneyAmount";

const meta = {
  title: "Domain/MoneyAmount",
  component: MoneyAmount,
  args: { value: { amount: "3200.00", currency: "HNL" } },
} satisfies Meta<typeof MoneyAmount>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Usd: Story = { args: { value: { amount: "3200.00", currency: "USD" } } };

export const Zero: Story = { args: { value: { amount: "0.00", currency: "HNL" } } };

export const Negative: Story = { args: { value: { amount: "-1500.00", currency: "HNL" } } };

export const Accounting: Story = { args: { value: { amount: "-1500.00", currency: "HNL" }, accounting: true } };

export const Large: Story = { args: { value: { amount: "9999999999.99", currency: "HNL" } } };

export const Sizes: Story = {
  render: (args) => (
    <div className="flex flex-col items-end gap-2">
      <MoneyAmount {...args} size="sm" />
      <MoneyAmount {...args} size="md" />
      <MoneyAmount {...args} size="lg" />
      <MoneyAmount {...args} size="xl" emphasis="strong" />
    </div>
  ),
};

export const ColorBySign: Story = {
  render: (args) => (
    <div className="flex flex-col items-end gap-2">
      <MoneyAmount {...args} colorBySign showPositiveSign value={{ amount: "50.00", currency: "HNL" }} />
      <MoneyAmount {...args} colorBySign value={{ amount: "-50.00", currency: "HNL" }} />
    </div>
  ),
};

// An amount outside the contract: the unavailable text, never a rounded figure.
export const Invalid: Story = { args: { value: { amount: "3200.005", currency: "HNL" } } };
