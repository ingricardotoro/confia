import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { student, studentPage } from "../../test/contract-responses.ts";
import { installMswServer, route } from "../../test/msw-server.ts";
import { renderFeature } from "../../test/render-feature.tsx";
import { studentsFeature } from "./feature.tsx";

// identity-session-permissions: the registry of students draws its write actions by the permissions the server asks for them
// (students:create, students:update) and the guardians dialog by guardians:update. The panel only hides.
installMswServer();

const TIMEOUT = { timeout: 10_000 };

async function openAs(permissions: readonly string[]) {
  route("get", "/api/v1/students", studentPage([student()]));
  renderFeature(studentsFeature, "/students", { permissions });
  const table = await screen.findByRole("table", { name: "Estudiantes de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
  return within(table);
}

describe("the students registry with a restricted role", () => {
  it("draws no create, edit, activate or guardians action for a role that only reads", async () => {
    const table = await openAs(["students:read"]);

    expect(table.queryByRole("button", { name: /^Editar / })).not.toBeInTheDocument();
    expect(table.queryByRole("button", { name: /^Desactivar / })).not.toBeInTheDocument();
    expect(table.queryByRole("button", { name: /Encargados de / })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Nuevo estudiante" })).not.toBeInTheDocument();
  });

  it("draws the edit and activate actions with students:update and the guardians action with guardians:update", async () => {
    const table = await openAs(["students:read", "students:update", "guardians:update"]);

    expect(table.getByRole("button", { name: /^Editar / })).toBeInTheDocument();
    expect(table.getByRole("button", { name: /^Desactivar / })).toBeInTheDocument();
    expect(table.getByRole("button", { name: /Encargados de / })).toBeInTheDocument();
  });
});
