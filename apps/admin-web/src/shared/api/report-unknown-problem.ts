/** What an unknown Problem may leave in a log: its status and its type, nothing the body said (AW55). */
export interface UnknownProblemFinding {
  readonly status: number;
  readonly type?: string;
}

const MAX_RECORD_LENGTH = 200;

/** The type without its query and its fragment, which may carry what the server or the person put there. */
export function stripQueryAndFragment(type: string): string {
  const end = type.search(/[?#]/);
  return end === -1 ? type : type.slice(0, end);
}

/**
 * Records an unknown Problem through an injectable function (`console.warn` by default). The record is
 * bounded to 200 characters and never carries `title`, `detail`, `instance`, a token or personal data
 * (CLAUDE.md, rule 11): the finding type has no field for them.
 */
export function reportUnknownProblem(
  { status, type }: UnknownProblemFinding,
  warn: (message: string) => void = (message) => {
    console.warn(message);
  },
): void {
  const base = `Unknown problem response: status ${String(status)}`;
  const record = type === undefined ? base : `${base}, type ${stripQueryAndFragment(type)}`;
  warn(record.slice(0, MAX_RECORD_LENGTH));
}
