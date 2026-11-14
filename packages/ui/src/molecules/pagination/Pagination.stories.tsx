import type { Meta, StoryObj } from "@storybook/react-vite";
import { Pagination } from "./Pagination";

const meta = {
  title: "Molecules/Pagination",
  component: Pagination,
  args: { page: 1, size: 50, total: 230, onPageChange: () => undefined },
} satisfies Meta<typeof Pagination>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const FirstPage: Story = { args: { page: 0 } };

export const LastPage: Story = { args: { page: 4 } };

export const Fetching: Story = { args: { disabled: true } };
