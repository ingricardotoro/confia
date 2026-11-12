import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { OptionField, type Option } from "../../organization/components/fields.tsx";
import type { EnrollmentStatus } from "../enrollment-model.ts";
import { ENROLLMENT_STATUSES } from "../enrollments-search.ts";

interface SelectFilter {
  readonly value: string;
  readonly options: readonly Option[];
  readonly onChange: (value: string) => void;
}

export interface EnrollmentFiltersProps {
  /** `withAll` is false while a year is in progress: the server then has no way to list every year. */
  readonly year: SelectFilter & { readonly withAll: boolean };
  readonly grade: SelectFilter;
  /** The sections of the year being shown. */
  readonly section: SelectFilter;
  readonly status: { readonly value: EnrollmentStatus | ""; readonly onChange: (status: EnrollmentStatus | "") => void };
}

/** The filters of the enrollments list: year (the one in progress by default), grade, section of that year, and status. */
export function EnrollmentFilters({ year, grade, section, status }: EnrollmentFiltersProps) {
  const { t } = useAdminText();
  return (
    <>
      <OptionField
        label={t("enrollments.filters.academicYear")}
        controlId="enrollments-filter-year"
        value={year.value}
        options={year.options}
        {...(year.withAll ? { placeholder: t("enrollments.filters.allYears") } : {})}
        onChange={year.onChange}
      />
      <OptionField
        label={t("enrollments.filters.grade")}
        controlId="enrollments-filter-grade"
        value={grade.value}
        options={grade.options}
        placeholder={t("enrollments.filters.allGrades")}
        onChange={grade.onChange}
      />
      <OptionField
        label={t("enrollments.filters.section")}
        controlId="enrollments-filter-section"
        value={section.value}
        options={section.options}
        placeholder={t("enrollments.filters.allSections")}
        onChange={section.onChange}
      />
      <OptionField
        label={t("enrollments.filters.status")}
        controlId="enrollments-filter-status"
        value={status.value}
        options={ENROLLMENT_STATUSES.map((value) => ({ value, label: t(`enrollments.status.${value}`) }))}
        placeholder={t("organization.common.allOption")}
        onChange={(value) => {
          status.onChange(ENROLLMENT_STATUSES.find((known) => known === value) ?? "");
        }}
      />
    </>
  );
}
