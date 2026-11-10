import { createHashHistory, createRootRoute, createRoute, createRouter } from "@tanstack/react-router";
import { GradeForm, GradeList, PeriodForm, PeriodList } from "./pages/config-academic";
import { FeeForm, FeeList, ScholarshipForm, ScholarshipList } from "./pages/config-fees";
import { DebtorList, PromiseForm, PromiseList, ReminderForm, ReminderList } from "./pages/collections";
import { AuditLog } from "./pages/audit";
import { Dashboard } from "./pages/dashboard";
import { CashPage, ChargeList, InvoiceList } from "./pages/extras";
import { GuardianDetail, GuardianForm, GuardianList } from "./pages/guardians";
import { OrgDetail, OrgForm, OrgList } from "./pages/orgs";
import { PaymentDetail, PaymentList, PaymentWizard } from "./pages/payments";
import { ClosingReport, IncomeReport, PortfolioReport } from "./pages/reports";
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
  r("/cobranza/morosos", DebtorList),
  r("/cobranza/promesas", PromiseList),
  r("/cobranza/promesas/nueva", () => <PromiseForm mode="new" />),
  r("/cobranza/promesas/$id/editar", () => <PromiseForm mode="edit" />),
  r("/cobranza/recordatorios", ReminderList),
  r("/cobranza/recordatorios/nuevo", ReminderForm),
  r("/reportes/ingresos", IncomeReport),
  r("/reportes/cartera", PortfolioReport),
  r("/reportes/cierres", ClosingReport),
  r("/auditoria", AuditLog),
  r("/configuracion/periodos", PeriodList),
  r("/configuracion/periodos/nuevo", () => <PeriodForm mode="new" />),
  r("/configuracion/periodos/$id/editar", () => <PeriodForm mode="edit" />),
  r("/configuracion/grados", GradeList),
  r("/configuracion/grados/nuevo", () => <GradeForm mode="new" />),
  r("/configuracion/grados/$id/editar", () => <GradeForm mode="edit" />),
  r("/configuracion/tarifas", FeeList),
  r("/configuracion/tarifas/nueva", () => <FeeForm mode="new" />),
  r("/configuracion/tarifas/$id/editar", () => <FeeForm mode="edit" />),
  r("/configuracion/becas", ScholarshipList),
  r("/configuracion/becas/nueva", () => <ScholarshipForm mode="new" />),
  r("/configuracion/becas/$id/editar", () => <ScholarshipForm mode="edit" />),
]);

export const router = createRouter({ routeTree, history: createHashHistory(), defaultPreload: false });
