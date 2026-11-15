/** The secret in groups of four separated by a space, which is how a person reads and types it (SS77). Display only. */
export function groupManualKey(secret: string): string {
  return (secret.match(/.{1,4}/g) ?? []).join(" ");
}
