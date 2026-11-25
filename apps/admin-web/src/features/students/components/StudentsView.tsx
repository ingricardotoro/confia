import { Button, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { ActiveBadge, RowActions } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceScreen, type ListTableState, type ResourceNotice, type ResourcePagination } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import { nameOf, type Student } from "../student-form.ts";
import { StudentFilters, type StudentFiltersProps } from "./StudentFilters.tsx";

export interface StudentsViewProps {
  readonly rows: readonly Student[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  readonly filters: StudentFiltersProps;
  /** Absent when the person cannot create: the button is not drawn. */
  readonly onCreate?: (() => void) | undefined;
  /** Absent when the person cannot edit: the button is not drawn. */
  readonly onEdit?: ((row: Student) => void) | undefined;
  /** Absent when the person cannot change the state: the button is not drawn. */
  readonly onToggle?: ((row: Student) => void) | undefined;
  /** Opens the dialog that manages the guardians of the student. */
  /** Absent when the person cannot manage guardians: the button is not drawn. */
  readonly onGuardians?: ((row: Student) => void) | undefined;
  /** The address of the statement of account of a student, so a link opens in a new tab and a plain click is a navigation of the router. */
  readonly statementHrefOf: (row: Student) => string;
  readonly onStatement: (row: Student) => void;
  /** The address of the charges of a student, so a link opens in a new tab and a plain click is a navigation of the router. */
  readonly chargesHrefOf: (row: Student) => string;
  /** The address of the scholarship awards of a student. Absent when the person cannot read them: the link is not drawn. */
  readonly awardsHrefOf?: ((row: Student) => string) | undefined;
  readonly onAwards?: ((row: Student) => void) | undefined;
  readonly onCharges: (row: Student) => void;
}

/**
 * The students as a table: code, last names, first names, birth date, sex, whether there is a document, state and actions,
 * with a text search and a status filter. The document column says only "registered" or a dash: the server never sends any digit.
 */
export function StudentsView({ rows, table, pagination, notice, togglingId, filters, onCreate, onEdit, onToggle, onGuardians, statementHrefOf, onStatement, chargesHrefOf, onCharges, awardsHrefOf, onAwards }: StudentsViewProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Student>[] = [
    { id: "studentCode", header: t("students.columns.studentCode"), sortable: true, cell: (row) => row.studentCode },
    { id: "lastName", header: t("students.columns.lastName"), sortable: true, cell: (row) => row.lastName },
    { id: "firstName", header: t("students.columns.firstName"), sortable: true, cell: (row) => row.firstName },
    { id: "birthDate", header: t("students.columns.birthDate"), sortable: true, cell: (row) => formatIsoDate(row.birthDate) },
    { id: "sex", header: t("students.columns.sex"), cell: (row) => t(`students.sex.${row.sex}`) },
    {
      id: "nationalId",
      header: t("students.columns.nationalId"),
      cell: (row) =>
        row.hasNationalId ? (
          <>
            <span aria-hidden="true">{t("students.documentRegistered")}</span>
            <span className="sr-only">{t("students.documentRegisteredText")}</span>
          </>
        ) : (
          <>
            <span aria-hidden="true">{t("students.noDocumentMark")}</span>
            <span className="sr-only">{t("students.noDocumentText")}</span>
          </>
        ),
    },
    { id: "status", header: t("resource.state"), cell: (row) => <ActiveBadge active={row.status === "ACTIVE"} /> },
    {
      id: "actions",
      header: t("resource.actions"),
      cell: (row) => (
        <RowActions
          name={nameOf(row)}
          active={row.status === "ACTIVE"}
          busy={togglingId === row.id}
          onEdit={onEdit === undefined ? undefined : () => { onEdit(row); }}
          onToggle={onToggle === undefined ? undefined : () => { onToggle(row); }}
          extra={
            <>
              {onGuardians === undefined ? null : (
                <Button
                  variant="secondary"
                  size="xs"
                  aria-label={t("students.guardiansActionFor", { name: nameOf(row) })}
                  onClick={() => {
                    onGuardians(row);
                  }}
                >
                  {t("students.guardiansAction")}
                </Button>
              )}
              <a
                href={statementHrefOf(row)}
                aria-label={t("students.statementActionFor", { name: nameOf(row) })}
                className="inline-flex h-7 items-center rounded-md border border-border-default px-2 text-xs font-medium text-text-primary underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus"
                onClick={(event) => {
                  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                  event.preventDefault();
                  onStatement(row);
                }}
              >
                {t("students.statementAction")}
              </a>
              <a
                href={chargesHrefOf(row)}
                aria-label={t("students.chargesActionFor", { name: nameOf(row) })}
                className="inline-flex h-7 items-center rounded-md border border-border-default px-2 text-xs font-medium text-text-primary underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus"
                onClick={(event) => {
                  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                  event.preventDefault();
                  onCharges(row);
                }}
              >
                {t("students.chargesAction")}
              </a>
              {awardsHrefOf === undefined || onAwards === undefined ? null : (
                <a
                  href={awardsHrefOf(row)}
                  aria-label={t("students.awardsActionFor", { name: nameOf(row) })}
                  className="inline-flex h-7 items-center rounded-md border border-border-default px-2 text-xs font-medium text-text-primary underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus"
                  onClick={(event) => {
                    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                    event.preventDefault();
                    onAwards(row);
                  }}
                >
                  {t("students.awardsAction")}
                </a>
              )}
            </>
          }
        />
      ),
    },
  ];

  return (
    <ResourceScreen
      heading={t("students.heading")}
      createLabel={t("students.create")}
      onCreate={onCreate}
      notice={notice}
      filters={<StudentFilters {...filters} />}
      table={{ ...table, caption: t("students.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
