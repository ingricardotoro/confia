import { useNavigate } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import {
  nameMapOf,
  optionsOf,
  useGradeLookup,
  useModalityLookup,
  useSectionLookup,
  useYearLookup,
} from "../../shared/organization-lookups/use-lookups.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { useResourceView } from "../../shared/resource-screen/use-resource-view.ts";
import type { ChargePlan } from "./charge-plan-form.ts";
import { referenceNamesOf, sectionNamesOf, sectionOptionsOf } from "./charge-plan-references.ts";
import { ChargePlanFormContainer } from "./ChargePlanFormContainer.tsx";
import { CHARGE_PLAN_SCOPES, CHARGE_PLAN_STATUSES, type ChargePlansSearch } from "./charges-search.ts";
import { chargePlanPath } from "./charges-paths.ts";
import { ChargePlansView } from "./components/ChargePlansView.tsx";
import { useChargePlanCreate, useChargePlanList } from "./use-charges-queries.ts";

export interface ChargePlansContainerProps {
  readonly search: ChargePlansSearch;
  readonly onSearchChange: SearchChange<ChargePlansSearch>;
}

/**
 * The charge plans: the list as the server serves it (filtered, ordered and paged there), the form of a new plan in a
 * dialog, and a link from each row to its detail. There is no edit and no delete here: a plan is edited, activated and
 * archived from its detail, and a plan is never deleted.
 */
export function ChargePlansContainer({ search, onSearchChange }: ChargePlansContainerProps) {
  const { can } = usePermissions();
  const { t } = useAdminText();
  const navigate = useNavigate();
  const list = useChargePlanList(toApiParams(search));
  const create = useChargePlanCreate();
  const view = useResourceView<ChargePlan>();
  const years = useYearLookup();
  const modalities = useModalityLookup();
  const grades = useGradeLookup();
  const sections = useSectionLookup();
  const lookups = [years, modalities, grades, sections];
  const lookupFailure = lookups.some((lookup) => lookup.failed)
    ? {
        onRetry: () => {
          for (const lookup of lookups) if (lookup.failed) lookup.retry();
        },
      }
    : undefined;
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.academicYearId !== undefined || search.targetScope !== undefined || search.status !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, academicYearId: undefined, targetScope: undefined, status: undefined },
    onCreate: can("charges:create") ? view.openCreate : undefined,
    loadFailedKey: "charges.plans.loadFailed",
    emptyKeys: {
      title: "charges.plans.emptyTitle",
      description: "charges.plans.emptyDescription",
      action: "charges.plans.emptyAction",
    },
    defaultSort: { columnId: "name", direction: "asc" },
  });
  const sectionNames = sectionNamesOf(
    sections.rows,
    grades.rows,
    years.rows,
    ({ grade, section, year }) => t("charges.plans.sectionOption", { grade, section, year }),
    t("resource.unknownReference"),
  );
  const active = <Row extends { readonly isActive: boolean }>(rows: readonly Row[]): Row[] => rows.filter((row) => row.isActive);

  return (
    <>
      <ChargePlansView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        yearNames={nameMapOf(years.rows)}
        referenceNames={referenceNamesOf(modalities.rows, grades.rows, sectionNames)}
        yearOptions={optionsOf(years.rows)}
        lookupFailure={lookupFailure}
        filters={{
          year: {
            value: search.academicYearId ?? "",
            onChange: (year) => {
              onSearchChange({ ...search, academicYearId: years.rows.find((known) => known.id === year)?.id, page: 1 }, { replace: true });
            },
          },
          scope: {
            value: search.targetScope ?? "",
            onChange: (scope) => {
              onSearchChange({ ...search, targetScope: CHARGE_PLAN_SCOPES.find((known) => known === scope), page: 1 }, { replace: true });
            },
          },
          status: {
            value: search.status ?? "",
            onChange: (status) => {
              onSearchChange({ ...search, status: CHARGE_PLAN_STATUSES.find((known) => known === status), page: 1 }, { replace: true });
            },
          },
        }}
        hrefOf={(row) => chargePlanPath(row.id)}
        onOpen={(row) => void navigate({ to: chargePlanPath(row.id) })}
        onCreate={can("charges:create") ? view.openCreate : undefined}
      />
      {view.dialog.kind === "create" ? (
        <ChargePlanFormContainer
          yearOptions={optionsOf(active(years.rows))}
          modalityOptions={optionsOf(active(modalities.rows))}
          gradeOptions={optionsOf(active(grades.rows))}
          sectionOptions={sectionOptionsOf(active(sections.rows), sectionNames)}
          lookupFailure={lookupFailure}
          save={async (body) => {
            const saved = await create.mutateAsync(body);
            view.saved("created", saved.name);
          }}
          onClose={view.close}
        />
      ) : null}
    </>
  );
}
