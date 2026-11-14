/**
 * The deadlines of the session, in local milliseconds, apart from the token store (design.md H1). They
 * are not secrets: the interface reads them. They live in module memory only, never in web storage, the
 * address or the history entry, and the next restore takes them from the refresh answer (SS129, SS130).
 */
export interface LocalDeadlines {
  readonly idleExpiresAt: number;
  readonly sessionExpiresAt: number;
  /** The local instant of the last refresh of this tab, which the cadence of activity reads; absent before any. */
  readonly lastRefreshAt: number | undefined;
}

export interface DeadlineStore {
  /**
   * Replaces the deadlines with the ones of the last answer, also when they are sooner: the server rules
   * (SS131). `refreshedAt` is given only by a refresh; without it the last one is kept.
   */
  record(deadlines: Pick<LocalDeadlines, "idleExpiresAt" | "sessionExpiresAt">, refreshedAt?: number): void;
  /** The same object until something changes. */
  snapshot(): LocalDeadlines | undefined;
  subscribe(listener: () => void): () => void;
  clear(): void;
}

/** The deadline at which the session ends: the sooner of the two (SS132). */
export function effectiveDeadline(deadlines: LocalDeadlines): number {
  return Math.min(deadlines.idleExpiresAt, deadlines.sessionExpiresAt);
}

export function createDeadlineStore(): DeadlineStore {
  let held: LocalDeadlines | undefined;
  const listeners = new Set<() => void>();

  function change(next: LocalDeadlines | undefined): void {
    held = next;
    for (const listener of [...listeners]) listener();
  }

  return {
    record({ idleExpiresAt, sessionExpiresAt }, refreshedAt) {
      const lastRefreshAt = refreshedAt ?? held?.lastRefreshAt;
      if (
        held?.idleExpiresAt === idleExpiresAt &&
        held.sessionExpiresAt === sessionExpiresAt &&
        held.lastRefreshAt === lastRefreshAt
      ) {
        return;
      }
      change({ idleExpiresAt, sessionExpiresAt, lastRefreshAt });
    },
    snapshot: () => held,
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    clear() {
      if (held !== undefined) change(undefined);
    },
  };
}
