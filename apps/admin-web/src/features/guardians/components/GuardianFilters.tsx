import { FormField, Input } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { OptionField } from "../../organization/components/fields.tsx";
import type { GuardianStatus } from "../guardian-form.ts";

export interface GuardianFiltersProps {
  readonly search: {
    /** What is typed, which may be ahead of the address. */
    readonly value: string;
    readonly onChange: (value: string) => void;
    /** One character is typed: too few to search. */
    readonly tooShort: boolean;
  };
  readonly status: { readonly value: GuardianStatus | ""; readonly onChange: (status: GuardianStatus | "") => void };
}

/**
 * The filters of the guardians list: a text search and the status. The hint under the search says what it looks in and,
 * with one character typed, that two are needed; the search asks the server only from two characters.
 */
export function GuardianFilters({ search, status }: GuardianFiltersProps) {
  const { t } = useAdminText();
  return (
    <>
      <FormField
        label={t("guardians.search.label")}
        controlId="guardians-search"
        description={t(search.tooShort ? "guardians.search.tooShort" : "guardians.search.help")}
        className="min-w-64"
      >
        {(control) => (
          <Input
            {...control}
            type="search"
            autoComplete="off"
            autoCorrect="off"
            spellCheck={false}
            maxLength={100}
            value={search.value}
            onChange={(event) => {
              search.onChange(event.target.value);
            }}
          />
        )}
      </FormField>
      <OptionField
        label={t("guardians.statusFilter")}
        controlId="guardians-filter-status"
        value={status.value}
        options={[
          { value: "ACTIVE", label: t("organization.common.active") },
          { value: "INACTIVE", label: t("organization.common.inactive") },
        ]}
        placeholder={t("organization.common.allOption")}
        onChange={(value) => {
          status.onChange(value === "ACTIVE" || value === "INACTIVE" ? value : "");
        }}
      />
    </>
  );
}
