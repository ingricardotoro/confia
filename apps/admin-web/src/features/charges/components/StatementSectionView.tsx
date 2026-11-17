import { Card, KeyFigure, MoneyAmount, Pagination, Table, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { StatementLine, StatementSection } from "../../../shared/api/charges-operations.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { periodLabelOf } from "../accrual-model.ts";
import { balanceSideOf, currencyNameOf, describeLine, directionOf, dueOnOf } from "../statement-model.ts";
import { StatementDirectionBadge } from "./StatementDirectionBadge.tsx";

export interface StatementSectionViewProps {
  readonly section: StatementSection;
  /** First and last day of the period, as the server resolved them (the default is the last 12 months). */
  readonly from: string;
  readonly to: string;
  /** A page of lines is being read: the pagination waits. */
  readonly busy: boolean;
  /** Zero based, like the server. */
  readonly onPageChange: (page: number) => void;
}

/** A figure that is zero in a column shows a dash, with the words for a screen reader; the figures that are not zero are the server's. */
function ColumnAmount({ value }: { readonly value: StatementLine["debit"] }) {
  const { t } = useAdminText();
  if (balanceSideOf(value) !== "zero") return <MoneyAmount value={value} size="sm" />;
  return (
    <>
      <span aria-hidden="true">{t("charges.statement.noAmountMark")}</span>
      <span className="sr-only">{t("charges.statement.noAmount")}</span>
    </>
  );
}

/** A balance with the words that say who owes whom, so a negative figure never relies on its sign or its colour. */
function BalanceAmount({ value, size, emphasis }: { readonly value: StatementLine["balance"]; readonly size: "sm" | "xl"; readonly emphasis?: "strong" }) {
  const { t } = useAdminText();
  return (
    <span className="inline-flex flex-wrap items-baseline justify-end gap-x-2">
      <MoneyAmount value={value} size={size} {...(emphasis === undefined ? {} : { emphasis })} />
      {balanceSideOf(value) === "credit" ? <span className="text-xs font-medium text-text-secondary">{t("charges.statement.credit")}</span> : null}
    </span>
  );
}

function sideDescriptionKey(value: StatementLine["balance"]) {
  const side = balanceSideOf(value);
  return side === "credit" ? "charges.statement.sideCredit" : side === "owed" ? "charges.statement.sideOwed" : "charges.statement.sideZero";
}

/**
 * One currency of the statement: the balance the period starts with, the balance it ends with (both worded «Saldo a favor» when the
 * student has credit), and the lines of the period with the running balance the server computed for each. The lines are one
 * page of the server's; the figures are shown as sent and nothing here adds, subtracts or rounds one.
 */
export function StatementSectionView({ section, from, to, busy, onPageChange }: StatementSectionViewProps) {
  const { t } = useAdminText();

  const columns: readonly TableColumn<StatementLine>[] = [
    { id: "effectiveOn", header: t("charges.statement.columns.effectiveOn"), cell: (line) => formatIsoDate(line.effectiveOn) },
    {
      id: "description",
      header: t("charges.statement.columns.description"),
      cell: (line) => {
        const description = describeLine(line);
        const direction = directionOf(line);
        const text =
          description.kind === "charge"
            ? t("charges.statement.chargeDescription", {
                concept: description.concept ?? t("resource.unknownReference"),
                period: periodLabelOf(description.periodStart),
              })
            : description.reason === undefined
              ? t(`charges.statement.kinds.${description.lineKind}`)
              : t("charges.statement.movementDescription", { kind: t(`charges.statement.kinds.${description.lineKind}`), reason: description.reason });
        return (
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span>{text}</span>
            {direction === undefined ? null : <StatementDirectionBadge direction={direction} />}
          </span>
        );
      },
    },
    {
      id: "dueOn",
      header: t("charges.statement.columns.dueOn"),
      cell: (line) => {
        const due = dueOnOf(line);
        return due === undefined ? t("charges.statement.noDueDate") : formatIsoDate(due);
      },
    },
    { id: "debit", header: t("charges.statement.columns.debit"), align: "end", cell: (line) => <ColumnAmount value={line.debit} /> },
    { id: "credit", header: t("charges.statement.columns.credit"), align: "end", cell: (line) => <ColumnAmount value={line.credit} /> },
    { id: "balance", header: t("charges.statement.columns.balance"), align: "end", cell: (line) => <BalanceAmount value={line.balance} size="sm" /> },
  ];

  return (
    <Card
      padding="sm"
      title={t("charges.statement.sectionTitle", { currency: currencyNameOf(section.currency), code: section.currency })}
    >
      <div className="flex flex-col gap-3">
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <KeyFigure
            label={t("charges.statement.opening")}
            value={<BalanceAmount value={section.openingBalance} size="xl" />}
            description={`${t("charges.statement.openingLabel", { date: formatIsoDate(from) })} ${t(sideDescriptionKey(section.openingBalance))}`}
          />
          <KeyFigure
            label={t("charges.statement.closing")}
            value={<BalanceAmount value={section.closingBalance} size="xl" emphasis="strong" />}
            description={`${t("charges.statement.closingLabel", { date: formatIsoDate(to) })} ${t(sideDescriptionKey(section.closingBalance))}`}
          />
        </div>
        <Table
          caption={t("charges.statement.caption", { currency: section.currency })}
          captionHidden
          density="compact"
          columns={columns}
          rows={section.lines}
          getRowId={(line) => `${line.transactionId}:${String(line.lineNumber)}`}
          empty={{
            title: t("charges.statement.noLinesTitle"),
            description: t("charges.statement.noLinesDescription"),
            action: {
              label: t("charges.statement.noLinesAction"),
              onAction: () => {
                document.getElementById("statement-from")?.focus();
              },
            },
          }}
        />
        <Pagination
          page={section.page}
          size={section.size}
          total={section.totalLines}
          onPageChange={onPageChange}
          disabled={busy}
          label={t("charges.statement.pagination", { currency: section.currency })}
        />
      </div>
    </Card>
  );
}
