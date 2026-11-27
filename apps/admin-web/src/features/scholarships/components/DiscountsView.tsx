import { Button, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { ActiveBadge, OptionField, RowActions } from "../../../shared/resource-screen/components/fields.tsx";
import {
  ResourceScreen,
  type ListTableState,
  type ResourceNotice,
  type ResourcePagination,
} from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import { approvalStateOf, isFrozen, mayOfferDecision } from "../discount-approval.ts";
import type { Discount } from "../discounts-types.ts";
import { DISCOUNT_KINDS } from "../discounts-search.ts";
import { BENEFIT_TYPES } from "../scholarships-search.ts";
import { DiscountApprovalBadge } from "./DiscountApprovalBadge.tsx";
import { BenefitValue } from "./DiscountTerms.tsx";

export interface DiscountsViewProps {
  readonly rows: readonly Discount[];
  readonly table: ListTableState;
  readonly pagination: ResourcePagination;
  readonly notice?: ResourceNotice | undefined;
  /** The id of the row being activated or deactivated, if any. */
  readonly togglingId?: string | undefined;
  /** The three filters. An empty value means "all". */
  readonly filters: {
    readonly kind: { readonly value: string; readonly onChange: (kind: string) => void };
    readonly benefitType: { readonly value: string; readonly onChange: (benefitType: string) => void };
    readonly active: { readonly value: string; readonly onChange: (active: string) => void };
  };
  /** Absent when the person cannot create: the button is not drawn. */
  readonly onCreate?: (() => void) | undefined;
  /** Absent when the person cannot edit: the button is not drawn. It is not drawn either for a discount that waits for approval or was rejected. */
  readonly onEdit?: ((row: Discount) => void) | undefined;
  /** The account of the session, to hide Approve and Reject from the person who created the discount or proposed the change. The server decides. */
  readonly accountId?: string | undefined;
  /** Absent when the person cannot approve (`scholarships:approve`): the buttons are not drawn. */
  readonly onApprove?: ((row: Discount) => void) | undefined;
  readonly onReject?: ((row: Discount) => void) | undefined;
  /** Absent when the person cannot change the state: the button is not drawn. */
  readonly onToggle?: ((row: Discount) => void) | undefined;
}

/**
 * The discounts as a table: code, name, class (campaign or agreement), the benefit (a percentage or a fixed amount, as the server holds it), the
 * validity, whether it stacks, the concepts it covers, the state and the actions, with three filters. Every amount is the server own, shown as it arrived.
 */
export function DiscountsView({ rows, table, pagination, notice, togglingId, filters, onCreate, onEdit, onToggle, accountId, onApprove, onReject }: DiscountsViewProps) {
  const { t } = useAdminText();
  const columns: readonly TableColumn<Discount>[] = [
    { id: "code", header: t("scholarships.discounts.columns.code"), sortable: true, cell: (row) => row.code },
    { id: "name", header: t("scholarships.discounts.columns.name"), sortable: true, cell: (row) => row.name },
    { id: "kind", header: t("scholarships.discounts.columns.kind"), cell: (row) => t(`scholarships.discounts.kinds.${row.kind}`) },
    {
      id: "benefit",
      header: t("scholarships.discounts.columns.benefit"),
      align: "end",
      cell: (row) => <BenefitValue terms={row} />,
    },
    {
      id: "validFrom",
      header: t("scholarships.discounts.columns.validity"),
      sortable: true,
      cell: (row) => <span className="tabular-nums">{t("scholarships.discounts.validityValue", { from: formatIsoDate(row.validFrom), to: formatIsoDate(row.validTo) })}</span>,
    },
    { id: "stackable", header: t("scholarships.discounts.columns.stackable"), cell: (row) => t(row.stackable ? "scholarships.discounts.stackableYes" : "scholarships.discounts.stackableNo") },
    { id: "concepts", header: t("scholarships.discounts.columns.concepts"), cell: (row) => row.concepts.map((concept) => concept.name).join(", ") },
    {
      id: "active",
      header: t("resource.state"),
      // The state of the discount, and under it what it waits for when it is not simply approved (pending, a proposal beside the terms in force, rejected).
      // One column, not two: the table is already wide, and a wider one scrolls and hides the first columns.
      cell: (row) => {
        const approval = approvalStateOf(row);
        return (
          <div className="flex flex-col items-start gap-1">
            <ActiveBadge active={row.active} />
            {approval === "approved" ? null : <DiscountApprovalBadge state={approval} />}
            {row.pendingChange === undefined ? null : (
              <span className="flex flex-wrap gap-x-1 text-xs text-text-secondary">
                <span>{t("scholarships.discounts.proposal.summary")}</span>
                <BenefitValue terms={row.pendingChange} />
                <span>{t("scholarships.discounts.validityValue", { from: formatIsoDate(row.pendingChange.validFrom), to: formatIsoDate(row.pendingChange.validTo) })}</span>
                {row.pendingChange.reactivate ? <span>{t("scholarships.discounts.proposal.reactivate")}</span> : null}
              </span>
            )}
          </div>
        );
      },
    },
    {
      id: "actions",
      header: t("resource.actions"),
      cell: (row) => (
        <RowActions
          name={row.name}
          active={row.active}
          busy={togglingId === row.id}
          onEdit={
            onEdit === undefined || isFrozen(row)
              ? undefined
              : () => {
                  onEdit(row);
                }
          }
          // Deactivating is always allowed; reactivating is not while the discount waits for approval or was rejected (409 discount-pending-approval).
          onToggle={
            onToggle === undefined || (!row.active && isFrozen(row))
              ? undefined
              : () => {
                  onToggle(row);
                }
          }
          extra={
            mayOfferDecision(row, accountId) && (onApprove !== undefined || onReject !== undefined) ? (
              <>
                {onReject === undefined ? null : (
                  <Button
                    variant="destructive-soft"
                    size="xs"
                    aria-label={t("scholarships.discounts.reject.buttonFor", { name: row.name })}
                    onClick={() => {
                      onReject(row);
                    }}
                  >
                    {t("scholarships.discounts.reject.button")}
                  </Button>
                )}
                {onApprove === undefined ? null : (
                  <Button
                    variant="success-soft"
                    size="xs"
                    aria-label={t("scholarships.discounts.approve.buttonFor", { name: row.name })}
                    onClick={() => {
                      onApprove(row);
                    }}
                  >
                    {t("scholarships.discounts.approve.button")}
                  </Button>
                )}
              </>
            ) : undefined
          }
        />
      ),
    },
  ];

  return (
    <ResourceScreen
      heading={t("scholarships.discounts.heading")}
      description={t("scholarships.discounts.description")}
      createLabel={t("scholarships.discounts.create")}
      onCreate={onCreate}
      notice={notice}
      filters={
        <>
          <OptionField
            label={t("scholarships.discounts.filterKind")}
            controlId="discounts-filter-kind"
            value={filters.kind.value}
            options={DISCOUNT_KINDS.map((kind) => ({ value: kind, label: t(`scholarships.discounts.kinds.${kind}`) }))}
            placeholder={t("resource.allOption")}
            onChange={filters.kind.onChange}
          />
          <OptionField
            label={t("scholarships.list.filterBenefitType")}
            controlId="discounts-filter-benefit-type"
            value={filters.benefitType.value}
            options={BENEFIT_TYPES.map((type) => ({ value: type, label: t(`scholarships.benefitTypes.${type}`) }))}
            placeholder={t("resource.allOption")}
            onChange={filters.benefitType.onChange}
          />
          <OptionField
            label={t("scholarships.list.filterActive")}
            controlId="discounts-filter-active"
            value={filters.active.value}
            options={[
              { value: "true", label: t("resource.active") },
              { value: "false", label: t("resource.inactive") },
            ]}
            placeholder={t("resource.allOption")}
            onChange={filters.active.onChange}
          />
        </>
      }
      table={{ ...table, caption: t("scholarships.discounts.caption"), columns, rows, getRowId: (row) => row.id }}
      pagination={pagination}
    />
  );
}
