import { createHashHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router";
import { Dashboard } from "./pages/dashboard";
import { CashPage, ChargeList, InvoiceList } from "./pages/extras";
import { GuardianDetail, GuardianForm, GuardianList } from "./pages/guardians";
import { OrgDetail, OrgForm, OrgList } from "./pages/orgs";
import { PaymentDetail, PaymentList, PaymentWizard } from "./pages/payments";
import { Shell } from "./pages/shell";
import { StudentDetail, StudentForm, StudentList } from "./pages/students";
import { UserDetail, UserForm, UserList } from "./pages/users";
import { Alert } from "@confia/ui";

const root = createRootRoute({
  component: Shell,
  notFoundComponent: () => <Alert variant="warning" title="Página no encontrada">La dirección que abrió no existe en el prototipo.</Alert>,
});
const r = (path: string, component: () => React.JSX.Element) => createRoute({ getParentRoute: () => root, path, component });

const routeTree = root.addChildren([
  r("/", Dashboard),
  r("/organizaciones", OrgList),
  r("/organizaciones/nueva", () => <OrgForm mode="new" />),
  r("/organizaciones/$id", OrgDetail),
  r("/organizaciones/$id/editar", () => <OrgForm mode="edit" />),
  r("/usuarios", UserList),
  r("/usuarios/nuevo", () => <UserForm mode="new" />),
  r("/usuarios/$id", UserDetail),
  r("/usuarios/$id/editar", () => <UserForm mode="edit" />),
  r("/estudiantes", StudentList),
  r("/estudiantes/nuevo", () => <StudentForm mode="new" />),
  r("/estudiantes/$id", StudentDetail),
  r("/estudiantes/$id/editar", () => <StudentForm mode="edit" />),
  r("/encargados", GuardianList),
  r("/encargados/nuevo", () => <GuardianForm mode="new" />),
  r("/encargados/$id", GuardianDetail),
  r("/encargados/$id/editar", () => <GuardianForm mode="edit" />),
  r("/pagos", PaymentList),
  r("/pagos/nuevo", PaymentWizard),
  r("/pagos/$id", PaymentDetail),
  r("/cargos", ChargeList),
  r("/caja", CashPage),
  r("/facturacion", InvoiceList),
]);

export const router = createRouter({ routeTree, history: createHashHistory(), defaultPreload: false });
