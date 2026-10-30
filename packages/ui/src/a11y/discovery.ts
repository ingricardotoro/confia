/**
 * The gate evaluates whatever the glob found, so a glob that matches nothing would pass every build
 * while checking nothing. ADR-0018: an empty set is a failure, never a pass.
 */
export function assertStoriesDiscovered(modules: Readonly<Record<string, unknown>>): void {
  if (Object.keys(modules).length === 0) {
    throw new Error(
      "Found zero stories under atoms/, molecules/ and domain/: the accessibility gate would check nothing (ADR-0018).",
    );
  }
}
