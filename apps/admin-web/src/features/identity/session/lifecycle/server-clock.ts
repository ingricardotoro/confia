/**
 * The clock of the server as this tab sees it (design.md H3): the last valid offset between the `Date`
 * header of an answer and the moment it arrived, and the conversion of the server instants with it.
 */
export interface ServerClock {
  /** Updates the offset only when `serverDate` and `receivedAt` are valid instants; anything else keeps the last one (SS139, SS140). */
  observe(serverDate: number | undefined, receivedAt: number): void;
  /** A server ISO instant as local milliseconds, with the last valid offset (zero before any). */
  toLocal(serverInstant: string): number;
}

/** The header has a resolution of one second: half of it centres the instant the server really had. */
const HEADER_RESOLUTION_HALF_MS = 500;

export function createServerClock(): ServerClock {
  let offset = 0;
  return {
    observe(serverDate, receivedAt) {
      if (serverDate === undefined || !Number.isFinite(serverDate) || !Number.isFinite(receivedAt)) return;
      offset = serverDate + HEADER_RESOLUTION_HALF_MS - receivedAt;
    },
    toLocal: (serverInstant) => Date.parse(serverInstant) - offset,
  };
}
