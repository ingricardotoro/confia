import { Alert, EmptyState, ErrorState, PageHeader, Skeleton } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { StudentStatementResponse } from "../charges-types.ts";
import { OptionField, TextField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import { StatementSectionView } from "./StatementSectionView.tsx";
import { StatementStudentPicker, type StatementStudentPickerProps } from "./StatementStudentPicker.tsx";

export type StatementState =
  /** No student is chosen: the page says how to start. */
  | { readonly kind: "idle" }
  /** The period the person typed ends before it starts: nothing is asked of the server. */
  | { readonly kind: "invalid-period" }
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly title: string; readonly description: string; readonly onRetry: () => void }
  /** The answer was no and will stay no: a student that is not in this institution (404) or a missing permission (403). Asking again changes nothing. */
  | { readonly kind: "blocked"; readonly title: string; readonly description: string }
  /** The student exists and has no account yet, so the server has no section to send. */
  | { readonly kind: "empty" }
  | { readonly kind: "ready"; readonly statement: StudentStatementResponse; readonly busy: boolean };

export interface StatementViewProps {
  readonly state: StatementState;
  readonly picker: StatementStudentPickerProps;
  readonly period: {
    readonly from: string;
    readonly to: string;
    readonly fromError?: string | undefined;
    readonly toError?: string | undefined;
    readonly onFromChange: (from: string) => void;
    readonly onToChange: (to: string) => void;
  };
  /** Present only when the student has more than one currency. The empty value is every currency (the placeholder «Todos»). */
  readonly currency?: { readonly value: string; readonly options: readonly Option[]; readonly onChange: (currency: string) => void } | undefined;
  /** Zero based, like the server; it pages the lines of every section. */
  readonly onPageChange: (page: number) => void;
}

const focusField = (id: string) => () => {
  document.getElementById(id)?.focus();
};

function Guidance({ state, onChooseAnother }: { readonly state: Extract<StatementState, { kind: "idle" | "invalid-period" | "empty" }>; readonly onChooseAnother: () => void }) {
  const { t } = useAdminText();
  if (state.kind === "idle") {
    return (
      <EmptyState
        title={t("charges.statement.idleTitle")}
        description={t("charges.statement.idleDescription")}
        action={{ label: t("charges.statement.idleAction"), onAction: focusField("statement-student") }}
      />
    );
  }
  if (state.kind === "invalid-period") {
    return (
      <EmptyState
        title={t("charges.statement.invalidPeriodTitle")}
        description={t("charges.statement.invalidPeriodDescription")}
        action={{ label: t("charges.statement.invalidPeriodAction"), onAction: focusField("statement-to") }}
      />
    );
  }
  return (
    <EmptyState
      title={t("charges.statement.emptyTitle")}
      description={t("charges.statement.emptyDescription")}
      action={{ label: t("charges.statement.emptyAction"), onAction: onChooseAnother }}
    />
  );
}

/**
 * The statement of account of a student: who (a searchable choice), which period and, when the student has more than one currency,
 * which currency, then one section per currency with its balances and the lines the server returned. It renders what it is given:
 * it asks for nothing and every figure is the server's, shown through the money component and never added, subtracted or rounded.
 */
export function StatementView({ state, picker, period, currency, onPageChange }: StatementViewProps) {
  const { t } = useAdminText();

  return (
    <section aria-labelledby="charge-statement-heading" className="flex flex-col gap-3">
      <PageHeader title={t("charges.statement.heading")} headingLevel={2} headingId="charge-statement-heading" description={t("charges.statement.description")} />
      <div className="flex flex-col gap-3 rounded-lg border border-border-subtle p-3">
        <StatementStudentPicker {...picker} />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <TextField
            label={t("charges.statement.from")}
            controlId="statement-from"
            kind="date"
            value={period.from}
            error={period.fromError}
            onChange={period.onFromChange}
            onBlur={() => undefined}
          />
          <TextField
            label={t("charges.statement.to")}
            controlId="statement-to"
            kind="date"
            value={period.to}
            error={period.toError}
            onChange={period.onToChange}
            onBlur={() => undefined}
          />
          {currency === undefined ? null : (
            <OptionField
              label={t("charges.statement.currency")}
              controlId="statement-currency"
              value={currency.value}
              options={currency.options}
              placeholder={t("resource.allOption")}
              onChange={currency.onChange}
            />
          )}
        </div>
        <p className="text-sm text-text-secondary">{t("charges.statement.periodHelp")}</p>
      </div>
      {state.kind === "loading" ? (
        <div aria-busy="true" className="flex flex-col gap-3">
          <p role="status" className="sr-only">
            {t("charges.statement.loading")}
          </p>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <Skeleton className="h-20 w-full" />
            <Skeleton className="h-20 w-full" />
          </div>
          <Skeleton className="h-48 w-full" />
        </div>
      ) : state.kind === "error" ? (
        <ErrorState title={state.title} description={state.description} onRetry={state.onRetry} />
      ) : state.kind === "blocked" ? (
        <Alert variant="warning">
          <p className="font-medium">{state.title}</p>
          <p className="mt-1">{state.description}</p>
        </Alert>
      ) : state.kind === "ready" ? (
        <div className="flex flex-col gap-3" aria-busy={state.busy || undefined}>
          {state.statement.sections.map((section) => (
            <StatementSectionView
              key={section.currency}
              section={section}
              from={state.statement.from}
              to={state.statement.to}
              busy={state.busy}
              onPageChange={onPageChange}
            />
          ))}
        </div>
      ) : (
        <Guidance state={state} onChooseAnother={picker.onClear} />
      )}
    </section>
  );
}
