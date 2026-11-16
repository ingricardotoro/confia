/** The four resources of the academic structure. */
export type OrganizationResource = "academic-years" | "modalities" | "grades" | "sections";

/**
 * Query keys of the academic structure, general to specific, so a prefix invalidates what it covers: the
 * area, one resource, its lists, one list. Nothing writes a literal key inline.
 */
export const organizationKeys = {
  all: ["organization"] as const,
  resource: (resource: OrganizationResource) => [...organizationKeys.all, resource] as const,
  lists: (resource: OrganizationResource) => [...organizationKeys.resource(resource), "list"] as const,
  list: (resource: OrganizationResource, params: object) => [...organizationKeys.lists(resource), params] as const,
};
