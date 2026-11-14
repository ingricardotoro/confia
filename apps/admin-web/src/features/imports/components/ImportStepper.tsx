import { Check } from "lucide-react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { IMPORT_STEPS, type ImportStep } from "../import-model.ts";

export interface ImportStepperProps {
  readonly current: ImportStep;
}

/**
 * The four steps of the import as an ordered list: the one in progress is marked with `aria-current="step"`, and each step
 * says whether it is done, current or pending in text too (the number and the weight do not carry it alone).
 */
export function ImportStepper({ current }: ImportStepperProps) {
  const { t } = useAdminText();
  const at = IMPORT_STEPS.indexOf(current);
  return (
    <nav aria-label={t("imports.stepsLabel")}>
      <ol className="flex flex-wrap gap-x-6 gap-y-2">
        {IMPORT_STEPS.map((step, index) => {
          const state = index < at ? "done" : index === at ? "current" : "pending";
          return (
            <li
              key={step}
              {...(state === "current" ? { "aria-current": "step" as const } : {})}
              className={`flex items-center gap-2 text-sm ${state === "current" ? "font-semibold text-text-primary" : "text-text-secondary"}`}
            >
              <span aria-hidden="true" className="inline-flex size-6 items-center justify-center rounded-full border border-border-subtle">
                {state === "done" ? <Check className="size-4" /> : index + 1}
              </span>
              <span>{t(`imports.steps.${step}`)}</span>
              <span className="sr-only">{t(`imports.stepState.${state}`)}</span>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
