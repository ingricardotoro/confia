import { Badge, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { ActiveBadge, OptionField, RowActions, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import {
  ResourceScreen,
  type ListTableState,
  type ResourceNotice,
  type ResourcePagination,
} from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { LedgerAccount } from "../account-form.ts";
import { ACCOUNT_TYPES } from "../ledger-search.ts";

export interface LedgerAccountsViewProps {
  readonly rows: readonly LedgerAccount[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  /** The two filters. An empty value means "all". */
  readonly filters: {
    readonly type: { readonly value: string; readonly onChange: (type: string) => void };
    readonly active: { readonly value: string; readonly onChange: (active: string) => void };
  };
  readonly onCreate: () => void;
  readonly onEdit: (row: LedgerAccount) => void;
  readonly onToggle: (row: LedgerAccount) => void;
}

/** The chart of accounts as a table: code, name, type, whether the account carries student balances, state and actions, with two filters. */
export function LedgerAccountsView({ rows, table, pagination, notice, togglingId, filters, onCreate, onEdit, onToggle }: LedgerAccountsViewProps) {
  const { t } = useAdminText();
  const typeOptions: readonly Option[] = ACCOUNT_TYPES.map((type) => ({ value: type, label: t(`ledger.types.${type}`) }));
  const activeOptions: readonly Option[] = [
    { value: "true", label: t("resource.active") },
    { value: "false", label: t("resource.inactive") },
  ];
  const columns: readonly TableColumn<LedgerAccount>[] = [
    { id: "code", header: t("ledger.accounts.columns.code"), sortable: true, cell: (row) => row.code },
    { id: "name", header: t("ledger.accounts.columns.name"), sortable: true, cell: (row) => row.name },
    {
      id: "type",
      header: t("ledger.accounts.columns.type"),
      cell: (row) => (
        <Badge variant="outline" size="sm">
          {t(`ledger.types.${row.type}`)}
        </Badge>
      ),
    },
    {
      id: "studentBalances",
      header: t("ledger.accounts.columns.studentBalances"),
      cell: (row) => t(row.holdsStudentBalances ? "ledger.common.yes" : "ledger.common.no"),
    },
    { id: "active", header: t("resource.state"), cell: (row) => <ActiveBadge active={row.active} /> },
    {
      id: "actions",
      header: t("resource.actions"),
      cell: (row) => (
        <RowActions
          name={row.name}
          active={row.active}
          busy={togglingId === row.id}
          onEdit={() => {
            onEdit(row);
          }}
          onToggle={() => {
            onToggle(row);
          }}
        />
      ),
    },
  ];

  return (
    <ResourceScreen
      heading={t("ledger.accounts.heading")}
      description={t("ledger.accounts.description")}
      createLabel={t("ledger.accounts.create")}
      onCreate={onCreate}
      notice={notice}
      filters={
        <>
          <OptionField
            label={t("ledger.accounts.filterType")}
            controlId="ledger-accounts-filter-type"
            value={filters.type.value}
            options={typeOptions}
            placeholder={t("resource.allOption")}
            onChange={filters.type.onChange}
          />
          <OptionField
            label={t("ledger.accounts.filterActive")}
            controlId="ledger-accounts-filter-active"
            value={filters.active.value}
            options={activeOptions}
            placeholder={t("resource.allOption")}
            onChange={filters.active.onChange}
          />
        </>
      }
      table={{ ...table, caption: t("ledger.accounts.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
