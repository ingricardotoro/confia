import type { Meta, StoryObj } from "@storybook/react-vite";
import { Table, type TableColumn, type TableProps } from "./Table";

// Row data and column headers are example data, not interface copy.
interface Row {
  id: string;
  name: string;
  grades: number;
}

const ROWS: Row[] = [
  { id: "1", name: "Educación regular", grades: 12 },
  { id: "2", name: "Educación nocturna", grades: 6 },
];

const COLUMNS: TableColumn<Row>[] = [
  { id: "name", header: "Modalidad", cell: (row) => row.name, sortable: true },
  { id: "grades", header: "Grados", cell: (row) => row.grades, align: "end", sortable: true },
];

const meta: Meta<TableProps<Row>> = {
  title: "Molecules/Table",
  component: Table,
  args: {
    caption: "Modalidades",
    columns: COLUMNS,
    rows: ROWS,
    getRowId: (row) => row.id,
    empty: {
      title: "Aún no hay modalidades",
      description: "Cree la primera para empezar a organizar los grados.",
      action: { label: "Crear modalidad", onAction: () => undefined },
    },
    onSortChange: () => undefined,
  },
};

export default meta;

type Story = StoryObj<TableProps<Row>>;

export const Default: Story = {};

export const SortedAscending: Story = { args: { sort: { columnId: "name", direction: "asc" } } };

export const SortedDescending: Story = { args: { sort: { columnId: "grades", direction: "desc" } } };

export const Loading: Story = { args: { loading: true, rows: [] } };

export const Empty: Story = { args: { rows: [] } };

export const Failed: Story = {
  args: {
    rows: [],
    error: {
      title: "No pudimos cargar las modalidades",
      description: "Revise su conexión e intente de nuevo.",
      onRetry: () => undefined,
    },
  },
};
