import { Link, Outlet, useNavigate, useRouterState } from "@tanstack/react-router";
import { Button, FormField, Input, Alert } from "@confia/ui";
import { ScrollText, BarChart3, BellRing, Landmark, PieChart, Handshake, TriangleAlert, Banknote, BadgePercent, Building2, CalendarRange, ClipboardList, School, Tags, FileText, GraduationCap, HandCoins, LayoutDashboard, LogOut, ReceiptText, UserCog, Users, Wallet } from "lucide-react";
import { useState, type FormEvent } from "react";
import { actions, useStore } from "../mock/store";
import { CURRENT_USER, INSTITUTION } from "../mock/data";
import { Toast } from "../lib/ui";
import { PortalShell } from "./portal";


const NAV = [
  { group: "General", items: [{ to: "/", label: "Resumen", icon: LayoutDashboard }] },
  {
    group: "Académico",
    items: [
      { to: "/estudiantes", label: "Estudiantes", icon: GraduationCap },
      { to: "/encargados", label: "Encargados", icon: Users },
    ],
  },
  {
    group: "Finanzas",
    items: [
      { to: "/pagos", label: "Pagos", icon: HandCoins },
      { to: "/cargos", label: "Cargos y estado de cuenta", icon: ClipboardList },
      { to: "/caja", label: "Caja", icon: Wallet },
      { to: "/facturacion", label: "Facturación", icon: ReceiptText },
    ],
  },
  {
    group: "Cobranza",
    items: [
      { to: "/cobranza/morosos", label: "Morosos", icon: TriangleAlert },
      { to: "/cobranza/promesas", label: "Promesas de pago", icon: Handshake },
      { to: "/cobranza/recordatorios", label: "Recordatorios", icon: BellRing },
    ],
  },
  {
    group: "Reportes",
    items: [
      { to: "/reportes/ingresos", label: "Ingresos", icon: BarChart3 },
      { to: "/reportes/cartera", label: "Cartera", icon: PieChart },
      { to: "/reportes/cierres", label: "Cierres de caja", icon: Landmark },
    ],
  },
  { group: "Auditoría", items: [{ to: "/auditoria", label: "Bitácora de auditoría", icon: ScrollText }] },
  {
    group: "Configuración",
    items: [
      { to: "/configuracion/periodos", label: "Años lectivos", icon: CalendarRange },
      { to: "/configuracion/grados", label: "Grados y secciones", icon: School },
      { to: "/configuracion/tarifas", label: "Tarifas y conceptos", icon: Tags },
      { to: "/configuracion/becas", label: "Becas y descuentos", icon: BadgePercent },
    ],
  },
  {
    group: "Administración",
    items: [
      { to: "/organizaciones", label: "Instituciones", icon: Building2 },
      { to: "/usuarios", label: "Usuarios del personal", icon: UserCog },
    ],
  },
];

export function Shell() {
  const signedIn = useStore((s) => s.signedIn);
  const toast = useStore((s) => s.toast);
  const simulateError = useStore((s) => s.simulateError);
  const navigate = useNavigate();
  const path = useRouterState({ select: (s) => s.location.pathname });
  if (path.startsWith("/portal")) return <PortalShell />;
  if (!signedIn) return <SignIn />;

  return (
    <div className="flex min-h-screen">
      <a href="#contenido" className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:bg-bg-surface focus:p-2">Saltar al contenido</a>
      <aside className="sticky top-0 flex h-screen w-64 shrink-0 flex-col border-r border-border-subtle bg-bg-surface">
        <div className="flex items-center gap-2 px-5 py-4">
          <span className="flex size-8 items-center justify-center rounded-md bg-primary text-primary-fg"><Banknote className="size-5" aria-hidden="true" /></span>
          <span className="text-lg font-semibold text-text-primary">CONFIA</span>
        </div>
        <nav aria-label="Principal" className="flex-1 overflow-y-auto px-3 pb-4">
          {NAV.map((g) => (
            <div key={g.group} className="mt-4">
              <p className="px-2 pb-1 text-xs font-semibold tracking-wide text-text-tertiary uppercase">{g.group}</p>
              <ul className="flex flex-col gap-0.5">
                {g.items.map((i) => (
                  <li key={i.to}>
                    <Link
                      to={i.to}
                      activeOptions={{ exact: i.to === "/" }}
                      className="flex items-center gap-3 rounded-md px-2 py-2 text-sm text-text-secondary hover:bg-bg-canvas focus-visible:outline-2 focus-visible:outline-border-focus"
                      activeProps={{ className: "bg-primary-soft-bg !text-primary-soft-fg font-medium", "aria-current": "page" }}
                    >
                      <i.icon className="size-4" aria-hidden="true" />
                      {i.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>
        <div className="flex flex-col gap-2 border-t border-border-subtle p-3 text-xs text-text-tertiary">
          <Link to="/portal" className="font-medium text-text-link hover:underline">Ver portal de encargados</Link>
          <span>Prototipo con datos ficticios</span>
        </div>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-10 flex items-center justify-between gap-4 border-b border-border-subtle bg-bg-surface px-6 py-3">
          <div>
            <p className="text-xs text-text-secondary">Institución</p>
            <p className="text-sm font-semibold text-text-primary">{INSTITUTION}</p>
          </div>
          <div className="flex items-center gap-4">
            <label className="flex items-center gap-2 text-xs text-text-secondary">
              <input type="checkbox" checked={simulateError} onChange={(e) => actions.setSimulateError(e.target.checked)} />
              Simular error de carga
            </label>
            <div className="text-right">
              <p className="text-sm font-medium text-text-primary">{CURRENT_USER.name}</p>
              <p className="text-xs text-text-secondary">{CURRENT_USER.role}</p>
            </div>
            <Button variant="secondary" size="sm" onClick={() => { actions.signOut(); void navigate({ to: "/" }); }}>
              <LogOut /> Cerrar sesión
            </Button>
          </div>
        </header>
        <main id="contenido" className="mx-auto w-full max-w-6xl flex-1 p-6">
          <Outlet />
        </main>
      </div>
      <Toast message={toast} />
    </div>
  );
}

function SignIn() {
  const [id, setId] = useState("");
  const [pw, setPw] = useState("");
  const [show, setShow] = useState(false);
  const submit = (e: FormEvent) => { e.preventDefault(); actions.signIn(); };
  return (
    <div className="flex min-h-screen items-center justify-center bg-bg-canvas p-4">
      <form onSubmit={submit} className="flex w-full max-w-sm flex-col gap-4 rounded-lg border border-border-subtle bg-bg-surface p-8 shadow-md">
        <div className="flex flex-col items-center gap-2 text-center">
          <span className="flex size-12 items-center justify-center rounded-lg bg-primary text-primary-fg"><FileText className="size-6" aria-hidden="true" /></span>
          <h1 className="text-xl font-semibold text-text-primary">Ingrese a CONFIA</h1>
          <p className="text-sm text-text-secondary">Control financiero académico</p>
        </div>
        <Alert variant="info">Prototipo: cualquier usuario y contraseña le permite entrar.</Alert>
        <FormField label="Usuario o correo electrónico">
          {(c) => <Input {...c} autoComplete="username" value={id} onChange={(e) => setId(e.target.value)} />}
        </FormField>
        <FormField label="Contraseña">
          {(c) => <Input {...c} type={show ? "text" : "password"} autoComplete="current-password" value={pw} onChange={(e) => setPw(e.target.value)} />}
        </FormField>
        <label className="flex items-center gap-2 text-sm text-text-secondary">
          <input type="checkbox" checked={show} onChange={(e) => setShow(e.target.checked)} /> Mostrar contraseña
        </label>
        <Button type="submit" size="lg">Ingresar</Button>
        <Link to="/portal" className="text-center text-sm font-medium text-text-link hover:underline">Entrar como encargado</Link>
      </form>
    </div>
  );
}
