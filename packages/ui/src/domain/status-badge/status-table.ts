import {
  AlertTriangle,
  Ban,
  CalendarClock,
  CheckCircle2,
  CircleDashed,
  CircleHelp,
  Clock3,
  RotateCcw,
  type LucideIcon,
} from "lucide-react";
import type { UiKey } from "../../i18n/resources";

// The single table state -> icon, catalog key and color token (docs/ui-ux/01 section 4 and 02 section
// 5.2). The component, the stories and the tests all read it, so a state cannot lose one of its three
// channels in one place and keep it in another.
export type FinancialStatus = "paid" | "pending" | "partial" | "overdue" | "void" | "promised" | "refunded";

export interface StatusEntry {
  readonly icon: LucideIcon;
  readonly textKey: UiKey;
  /** Binds the --sb-* variables of the badge to the --status-badge-* tokens of this state. */
  readonly tone: string;
}

// The class names are written out in full: Tailwind finds a utility by reading the source, and a name
// assembled from a template would never reach the stylesheet.
const TONES = {
  paid:
    "[--sb-bg:var(--status-badge-paid-bg)] [--sb-fg:var(--status-badge-paid-fg)] " +
    "[--sb-border:var(--status-badge-paid-border)] [--sb-solid:var(--status-badge-paid-solid-bg)]",
  pending:
    "[--sb-bg:var(--status-badge-pending-bg)] [--sb-fg:var(--status-badge-pending-fg)] " +
    "[--sb-border:var(--status-badge-pending-border)] [--sb-solid:var(--status-badge-pending-solid-bg)]",
  partial:
    "[--sb-bg:var(--status-badge-partial-bg)] [--sb-fg:var(--status-badge-partial-fg)] " +
    "[--sb-border:var(--status-badge-partial-border)] [--sb-solid:var(--status-badge-partial-solid-bg)]",
  overdue:
    "[--sb-bg:var(--status-badge-overdue-bg)] [--sb-fg:var(--status-badge-overdue-fg)] " +
    "[--sb-border:var(--status-badge-overdue-border)] [--sb-solid:var(--status-badge-overdue-solid-bg)]",
  void:
    "[--sb-bg:var(--status-badge-void-bg)] [--sb-fg:var(--status-badge-void-fg)] " +
    "[--sb-border:var(--status-badge-void-border)] [--sb-solid:var(--status-badge-void-solid-bg)]",
  promised:
    "[--sb-bg:var(--status-badge-promised-bg)] [--sb-fg:var(--status-badge-promised-fg)] " +
    "[--sb-border:var(--status-badge-promised-border)] [--sb-solid:var(--status-badge-promised-solid-bg)]",
  refunded:
    "[--sb-bg:var(--status-badge-refunded-bg)] [--sb-fg:var(--status-badge-refunded-fg)] " +
    "[--sb-border:var(--status-badge-refunded-border)] [--sb-solid:var(--status-badge-refunded-solid-bg)]",
} as const satisfies Record<FinancialStatus, string>;

export const STATUS_TABLE = {
  paid: { icon: CheckCircle2, textKey: "common.status.paid", tone: TONES.paid },
  pending: { icon: Clock3, textKey: "common.status.pending", tone: TONES.pending },
  partial: { icon: CircleDashed, textKey: "common.status.partial", tone: TONES.partial },
  overdue: { icon: AlertTriangle, textKey: "common.status.overdue", tone: TONES.overdue },
  void: { icon: Ban, textKey: "common.status.void", tone: TONES.void },
  promised: { icon: CalendarClock, textKey: "common.status.promised", tone: TONES.promised },
  refunded: { icon: RotateCcw, textKey: "common.status.refunded", tone: TONES.refunded },
} as const satisfies Record<FinancialStatus, StatusEntry>;

export const FINANCIAL_STATUSES = Object.keys(STATUS_TABLE) as FinancialStatus[];

/** What a value outside the seven states shows: neutral, with an icon, and no state color. */
export const UNKNOWN_STATUS: StatusEntry = { icon: CircleHelp, textKey: "common.status.unknown", tone: "" };
