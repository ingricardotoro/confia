/**
 * A stand-in for `navigator.locks` (Web Locks): an exclusive queue in first come, first served order,
 * which is what the browser gives two tabs asking for the same name. It records what was asked, so a
 * test can assert the name and the mode, and it lets a test hold the lock the way another tab would.
 */
export interface FakeLocks {
  readonly requested: { readonly name: string; readonly mode: "exclusive" }[];
  request<T>(name: string, options: { readonly mode: "exclusive" }, callback: () => Promise<T>): Promise<T>;
}

export function createFakeLocks(): FakeLocks {
  let tail: Promise<unknown> = Promise.resolve();
  const requested: FakeLocks["requested"] = [];
  return {
    requested,
    request<T>(name: string, options: { readonly mode: "exclusive" }, callback: () => Promise<T>): Promise<T> {
      requested.push({ name, mode: options.mode });
      const run = tail.then(callback);
      tail = run.catch(() => undefined);
      return run;
    },
  };
}
