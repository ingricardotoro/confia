import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { Permission } from "./permission-codes.ts";
import { PermissionsProvider, usePermissions } from "./permissions.tsx";
import { requiredPermissionFor, type RoutePermission } from "./route-permissions.ts";

function Probe({ permission }: { readonly permission: Permission }) {
  const { can } = usePermissions();
  return <p>{can(permission) ? "yes" : "no"}</p>;
}

describe("usePermissions", () => {
  it("grants exactly the codes the session lists", () => {
    render(
      <PermissionsProvider permissions={["charges:read"]}>
        <Probe permission="charges:read" />
        <Probe permission="charges:void" />
      </PermissionsProvider>,
    );

    expect(screen.getAllByRole("paragraph").map((node) => node.textContent)).toEqual(["yes", "no"]);
  });

  it("grants nothing outside a provider", () => {
    render(<Probe permission="charges:read" />);

    expect(screen.getByText("no")).toBeInTheDocument();
  });

  it("does not turn a code that is not in the list into a grant, whatever its spelling", () => {
    render(
      <PermissionsProvider permissions={["CHARGES:READ", "charges:*", " charges:read"]}>
        <Probe permission="charges:read" />
      </PermissionsProvider>,
    );

    expect(screen.getByText("no")).toBeInTheDocument();
  });
});

describe("requiredPermissionFor", () => {
  const guards: readonly RoutePermission[] = [
    { path: "/charges", permission: "charges:read" },
    { path: "/charges/accrual", permission: "charges:create" },
    { path: "/charges/accrual/runs", permission: "charges:read" },
  ];

  it("takes the permission of the longest declared prefix", () => {
    expect(requiredPermissionFor("/charges/plans/42", guards)).toBe("charges:read");
    expect(requiredPermissionFor("/charges/accrual", guards)).toBe("charges:create");
    expect(requiredPermissionFor("/charges/accrual/runs/7", guards)).toBe("charges:read");
  });

  it("ignores a trailing slash and does not match a path that only shares the letters", () => {
    expect(requiredPermissionFor("/charges/accrual/", guards)).toBe("charges:create");
    expect(requiredPermissionFor("/charges-extra", guards)).toBeUndefined();
  });

  it("asks for nothing on an address no declaration covers", () => {
    expect(requiredPermissionFor("/", guards)).toBeUndefined();
  });
});
