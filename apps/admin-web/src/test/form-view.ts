import { createRef } from "react";
import { vi } from "vitest";
import type { ResourceFormView } from "../features/organization/components/ResourceFormDialog.tsx";

/** What a form container hands its presentation, with nothing wrong and nothing in flight, for a presentation test. */
export function formView(override: Partial<ResourceFormView> = {}): ResourceFormView {
  return {
    summary: [],
    liveMessage: "",
    submitting: false,
    summaryRef: createRef<HTMLDivElement>(),
    alertRef: createRef<HTMLDivElement>(),
    onSubmit: vi.fn((event: { preventDefault: () => void }) => {
      event.preventDefault();
    }),
    ...override,
  };
}

/** A table state that is loaded and not empty, for a view test. */
export const loadedTable = {
  empty: { title: "vacío", action: { label: "crear", onAction: () => undefined } },
  onSortChange: () => undefined,
} as const;

export const onePage = { page: 0, size: 25, total: 1, busy: false, onPageChange: () => undefined } as const;
