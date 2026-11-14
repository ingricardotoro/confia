import { Button, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { ActiveBadge, RowActions } from "../../organization/components/fields.tsx";
import { ResourceScreen, type ListTableState, type ResourceNotice, type ResourcePagination } from "../../organization/components/ResourceScreen.tsx";
import { nameOf, type Guardian } from "../guardian-form.ts";
import { GuardianFilters, type GuardianFiltersProps } from "./GuardianFilters.tsx";

export interface GuardiansViewProps {
  readonly rows: readonly Guardian[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  readonly filters: GuardianFiltersProps;
  readonly onCreate: () => void;
  readonly onEdit: (row: Guardian) => void;
  readonly onToggle: (row: Guardian) => void;
  readonly onDetail: (row: Guardian) => void;
}

function Registered({ yes }: { readonly yes: boolean }) {
  const { t } = useAdminText();
  return yes ? (
    <>
      <span aria-hidden="true">{t("guardians.registered")}</span>
      <span className="sr-only">{t("guardians.registeredText")}</span>
    </>
  ) : (
    <>
      <span aria-hidden="true">{t("guardians.noneMark")}</span>
      <span className="sr-only">{t("guardians.noneText")}</span>
    </>
  );
}

/**
 * The guardians as a table: code, last names, first names, whether there is a document and an RTN, state and actions, with
 * a text search and a status filter. The document and the RTN columns say only "registered" or a dash: the server never
 * sends a digit of either.
 */
export function GuardiansView({ rows, table, pagination, notice, togglingId, filters, onCreate, onEdit, onToggle, onDetail }: GuardiansViewProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Guardian>[] = [
    { id: "code", header: t("guardians.columns.code"), sortable: true, cell: (row) => row.guardianCode },
    { id: "lastName", header: t("guardians.columns.lastName"), sortable: true, cell: (row) => row.lastName },
    { id: "firstName", header: t("guardians.columns.firstName"), cell: (row) => row.firstName },
    { id: "nationalId", header: t("guardians.columns.nationalId"), cell: (row) => <Registered yes={row.hasNationalId} /> },
    { id: "rtn", header: t("guardians.columns.rtn"), cell: (row) => <Registered yes={row.hasRtn} /> },
    { id: "status", header: t("guardians.columns.status"), sortable: true, cell: (row) => <ActiveBadge active={row.status === "ACTIVE"} /> },
    {
      id: "actions",
      header: t("organization.common.actions"),
      cell: (row) => (
        <RowActions
          name={nameOf(row)}
          active={row.status === "ACTIVE"}
          busy={togglingId === row.id}
          onEdit={() => {
            onEdit(row);
          }}
          onToggle={() => {
            onToggle(row);
          }}
          extra={
            <Button
              variant="secondary"
              size="sm"
              aria-label={t("guardians.actions.detailFor", { name: nameOf(row) })}
              onClick={() => {
                onDetail(row);
              }}
            >
              {t("guardians.actions.detail")}
            </Button>
          }
        />
      ),
    },
  ];

  return (
    <ResourceScreen
      heading={t("guardians.heading")}
      createLabel={t("guardians.create")}
      onCreate={onCreate}
      notice={notice}
      filters={<GuardianFilters {...filters} />}
      table={{ ...table, caption: t("guardians.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
