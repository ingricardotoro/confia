import { Badge, Button, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { ResourceScreen, type ListTableState, type ResourceNotice, type ResourcePagination } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import { detailButtonId, studentNameOf, type Enrollment } from "../enrollment-model.ts";
import { EnrollmentFilters, type EnrollmentFiltersProps } from "./EnrollmentFilters.tsx";

export interface EnrollmentsViewProps {
  readonly rows: readonly Enrollment[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  readonly filters: EnrollmentFiltersProps;
  /** Set when the lists behind the filters failed to load. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  /** Absent when the person cannot enroll: the button is not drawn. */
  readonly onCreate?: (() => void) | undefined;
  readonly onDetail: (row: Enrollment) => void;
  /** Absent when the person cannot update enrollments: the buttons are not drawn. */
  readonly onTransfer?: ((row: Enrollment) => void) | undefined;
  readonly onExit?: ((row: Enrollment) => void) | undefined;
}

/**
 * The enrollments as a table: student code and name, grade and section, the day of enrollment, the state and the day of exit.
 * Every row offers the detail; only an active enrollment offers a section transfer and an exit, because the other states are final.
 * The state is text in a badge, never color alone.
 */
export function EnrollmentsView({ rows, table, pagination, notice, filters, lookupFailure, onCreate, onDetail, onTransfer, onExit }: EnrollmentsViewProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Enrollment>[] = [
    { id: "studentCode", header: t("enrollments.columns.studentCode"), cell: (row) => row.studentCode },
    {
      id: "studentLastName",
      header: t("enrollments.columns.student"),
      sortable: true,
      cell: (row) => `${row.studentLastName}, ${row.studentFirstName}`,
    },
    { id: "gradeSection", header: t("enrollments.columns.gradeSection"), cell: (row) => `${row.gradeName} · ${row.sectionName}` },
    { id: "enrolledOn", header: t("enrollments.columns.enrolledOn"), sortable: true, cell: (row) => formatIsoDate(row.enrolledOn) },
    {
      id: "status",
      header: t("enrollments.columns.status"),
      sortable: true,
      cell: (row) => <Badge variant={row.status === "ACTIVE" ? "primary" : "outline"}>{t(`enrollments.status.${row.status}`)}</Badge>,
    },
    {
      id: "withdrawnOn",
      header: t("enrollments.columns.withdrawnOn"),
      cell: (row) =>
        row.withdrawnOn == null ? (
          <>
            <span aria-hidden="true">{t("enrollments.noExitDate")}</span>
            <span className="sr-only">{t("enrollments.noExitDateText")}</span>
          </>
        ) : (
          formatIsoDate(row.withdrawnOn)
        ),
    },
    {
      id: "actions",
      header: t("resource.actions"),
      cell: (row) => {
        const name = studentNameOf(row);
        return (
          <div className="flex flex-wrap gap-1.5">
            <Button
              id={detailButtonId(row.id)}
              variant="secondary"
              size="xs"
              aria-label={t("enrollments.actions.detailFor", { name })}
              onClick={() => {
                onDetail(row);
              }}
            >
              {t("enrollments.actions.detail")}
            </Button>
            {row.status === "ACTIVE" && (onTransfer !== undefined || onExit !== undefined) ? (
              <>
                {onTransfer === undefined ? null : (
                  <Button
                    variant="secondary"
                    size="xs"
                    aria-label={t("enrollments.actions.transferFor", { name })}
                    onClick={() => {
                      onTransfer(row);
                    }}
                  >
                    {t("enrollments.actions.transfer")}
                  </Button>
                )}
                {onExit === undefined ? null : (
                  <Button
                    variant="destructive-soft"
                    size="xs"
                    aria-label={t("enrollments.actions.exitFor", { name })}
                    onClick={() => {
                      onExit(row);
                    }}
                  >
                    {t("enrollments.actions.exit")}
                  </Button>
                )}
              </>
            ) : null}
          </div>
        );
      },
    },
  ];

  return (
    <ResourceScreen
      heading={t("enrollments.heading")}
      createLabel={t("enrollments.create")}
      onCreate={onCreate}
      notice={notice}
      lookupFailure={lookupFailure}
      filters={<EnrollmentFilters {...filters} />}
      table={{ ...table, caption: t("enrollments.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
