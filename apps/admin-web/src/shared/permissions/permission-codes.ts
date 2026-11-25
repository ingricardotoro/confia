/**
 * The permission codes the panel asks about. They are the codes the server publishes in the
 * `x-required-permission` of each operation of the admin API (`apps/api/openapi/admin.openapi.json`) and
 * returns in `permissions` of the current session; none is invented here. A screen that needs a new one
 * adds it to this list.
 *
 * The panel only hides what the person cannot use. The server decides every request again.
 */
export const PERMISSION_CODES = [
  "cashbox:read",
  "cashbox:create",
  "cashbox:approve",
  "catalog:read",
  "catalog:create",
  "catalog:update",
  "charges:read",
  "charges:create",
  "charges:update",
  "charges:void",
  "guardians:read",
  "guardians:create",
  "guardians:update",
  "ledger:read",
  "ledger:create",
  "organization:read",
  "organization:create",
  "organization:update",
  "payments:read",
  "payments:create",
  "payments:approve",
  "scholarships:read",
  "scholarships:create",
  "scholarships:approve",
  "scholarships:void",
  "students:read",
  "students:create",
  "students:update",
] as const;

export type Permission = (typeof PERMISSION_CODES)[number];
