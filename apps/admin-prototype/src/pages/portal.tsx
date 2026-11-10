import { Link, Outlet, useNavigate, useParams } from "@tanstack/react-router";
import { Alert, Button, EmptyState, FormField, Input } from "@confia/ui";
import { Banknote, FileText, Home, LogOut, Receipt, Settings } from "lucide-react";
import { useState, type FormEvent, type ReactNode } from "react";
import { Amount, Card, LoadGate, Modal, PageHeader, Pill, Select, StatusFor, Toast, displayTyped, isTypedAmount, type Tone } from "../lib/ui";
import type { Payment } from "../mock/data";
import { PORTAL_GUARDIAN_ID, type ReportStatus } from "../mock/portal-data";
import { actions, nextId, notify, useFakeLoad, useStore } from "../mock/store";

const NAV = [
  { to: "/portal", label: "Inicio", icon: Home, exact: true },
  { to: "/portal/pagos", label: "Pagos", icon: Receipt, exact: false },
  { to: "/portal/reportar", label: "Reportar pago", icon: FileText, exact: false },
  { to: "/portal/preferencias", label: "Preferencias", icon: Settings, exact: false },
];
const goHome = () => { window.location.hash = "#/portal"; };
const REPORT_TONE: Record<ReportStatus, Tone> = { "Pendiente de revisión": "primary", Aprobado: "success", Rechazado: "danger" };

/** Guardian-facing layout: top bar on wide screens, fixed bottom bar on phones. */
export function PortalShell() {
  const toast = useStore((s) => s.toast);
  const simulateError = useStore((s) => s.simulateError);
  const guardian = useStore((s) => s.guardians.find((g) => g.id === PORTAL_GUARDIAN_ID));
  const navigate = useNavigate();
  return (
    <div className="flex min-h-screen flex-col bg-bg-canvas">
      <a href="#contenido" className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:bg-bg-surface focus:p-2">Saltar al contenido</a>
      <header className="sticky top-0 z-10 border-b border-border-subtle bg-bg-surface">
        <div className="mx-auto flex max-w-3xl items-center justify-between gap-2 px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary text-primary-fg"><Banknote className="size-5" aria-hidden="true" /></span>
            <div className="leading-tight"><p className="text-sm font-semibold text-text-primary">CONFIA</p><p className="text-xs text-text-secondary">Portal de encargados</p></div>
          </div>
          <div className="flex items-center gap-2">
            <label className="hidden items-center gap-1 text-xs text-text-secondary sm:flex"><input type="checkbox" checked={simulateError} onChange={(e) => actions.setSimulateError(e.target.checked)} /> Simular error de carga</label>
            <span className="hidden text-sm text-text-primary sm:inline">{guardian?.name}</span>
            <Button variant="secondary" size="sm" onClick={() => { actions.signOut(); void navigate({ to: "/" }); }}><LogOut /><span className="sr-only sm:not-sr-only">Cerrar sesión</span></Button>
          </div>
        </div>
        <nav aria-label="Portal" className="mx-auto hidden max-w-3xl gap-1 px-4 sm:flex">
          {NAV.map((i) => (
            <Link key={i.to} to={i.to} activeOptions={{ exact: i.exact }} className="-mb-px border-b-2 border-transparent px-3 py-2 text-sm text-text-secondary hover:text-text-primary focus-visible:outline-2 focus-visible:outline-border-focus" activeProps={{ className: "!border-primary !text-primary font-medium", "aria-current": "page" }}>{i.label}</Link>
          ))}
        </nav>
      </header>
      <main id="contenido" className="mx-auto w-full max-w-3xl flex-1 px-4 py-5 pb-24 sm:pb-8">
        <Outlet />
      </main>
      <nav aria-label="Portal (móvil)" className="fixed inset-x-0 bottom-0 z-20 grid grid-cols-4 border-t border-border-subtle bg-bg-surface sm:hidden">
        {NAV.map((i) => (
          <Link key={i.to} to={i.to} activeOptions={{ exact: i.exact }} className="flex flex-col items-center gap-0.5 px-1 py-2 text-[11px] text-text-secondary focus-visible:outline-2 focus-visible:outline-border-focus" activeProps={{ className: "!text-primary font-medium", "aria-current": "page" }}>
            <i.icon className="size-5" aria-hidden="true" />{i.label}
          </Link>
        ))}
      </nav>
      <Toast message={toast} />
    </div>
  );
}

function useChildren() {
  const g = useStore((s) => s.guardians.find((x) => x.id === PORTAL_GUARDIAN_ID));
  const students = useStore((s) => s.students);
  return students.filter((s) => g?.studentIds.includes(s.id));
}

/** Rows as cards on phones and as a table from the sm breakpoint up. */
function ResponsiveRows({ caption, head, rows, rightFrom = [] }: { caption: string; head: string[]; rows: { key: string; cells: ReactNode[] }[]; rightFrom?: number[] }) {
  return (
    <>
      <ul className="flex flex-col gap-3 sm:hidden" aria-label={caption}>
        {rows.map((r) => (
          <li key={r.key} className="rounded-md border border-border-subtle p-3">
            <dl className="flex flex-col gap-1 text-sm">
              {r.cells.map((c, i) => <div key={i} className="flex items-start justify-between gap-3"><dt className="text-text-secondary">{head[i]}</dt><dd className="text-right text-text-primary">{c}</dd></div>)}
            </dl>
          </li>
        ))}
      </ul>
      <div className="hidden overflow-x-auto sm:block">
        <table className="w-full text-sm">
          <caption className="sr-only">{caption}</caption>
          <thead className="text-text-secondary"><tr>{head.map((h, i) => <th key={h} scope="col" className={`px-2 py-2 font-medium ${rightFrom.includes(i) ? "text-right" : "text-left"}`}>{h}</th>)}</tr></thead>
          <tbody className="divide-y divide-border-subtle">{rows.map((r) => <tr key={r.key}>{r.cells.map((c, i) => <td key={i} className={`px-2 py-2 ${rightFrom.includes(i) ? "text-right" : "text-left"}`}>{c}</td>)}</tr>)}</tbody>
        </table>
      </div>
    </>
  );
}

export function PortalHome() {
  const { status, retry } = useFakeLoad("portal-home");
  const guardian = useStore((s) => s.guardians.find((g) => g.id === PORTAL_GUARDIAN_ID));
  const children = useChildren();
  const reports = useStore((s) => s.paymentReports);
  const pending = reports.filter((r) => r.status === "Pendiente de revisión");
  return (
    <>
      <PageHeader title={`Hola, ${guardian?.name.split(" ")[0] ?? ""}`} subtitle="Estos son sus hijos y el saldo de cada uno. Los saldos los calcula el colegio." />
      <LoadGate status={status} retry={retry}>
        {children.length === 0 ? (
          <Card><EmptyState title="No hay estudiantes vinculados" description="Comuníquese con la administración del colegio para vincular a sus hijos." action={{ label: "Volver al inicio", onAction: goHome }} /></Card>
        ) : (
          <div className="flex flex-col gap-4">
            {pending.length > 0 ? <Alert variant="info" title="Tiene pagos reportados en revisión">Reportes pendientes: {pending.length}. Le avisaremos cuando el colegio los confirme.</Alert> : null}
            {children.map((c) => (
              <Card key={c.id}>
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <h2 className="text-base font-semibold text-text-primary">{c.name}</h2>
                    <p className="text-sm text-text-secondary">{c.grade}, sección {c.section} · {c.shift}</p>
                  </div>
                  <StatusFor status={c.balanceStatus} />
                </div>
                <p className="mt-4 text-sm text-text-secondary">Saldo pendiente</p>
                <p className="text-2xl font-semibold text-text-primary"><Amount value={c.balance} strong /></p>
                {c.balanceStale ? <p className="mt-1 text-xs text-text-secondary">El saldo se está actualizando.</p> : null}
                <div className="mt-4 flex flex-wrap gap-2">
                  <Link to="/portal/hijo/$id" params={{ id: c.id }}><Button>Ver estado de cuenta</Button></Link>
                  <Link to="/portal/reportar"><Button variant="secondary">Reportar un pago</Button></Link>
                </div>
              </Card>
            ))}
          </div>
        )}
      </LoadGate>
    </>
  );
}

export function PortalStatement() {
  const { id } = useParams({ strict: false }) as { id?: string };
  const { status, retry } = useFakeLoad(`portal-statement-${id}`);
  const child = useChildren().find((c) => c.id === id);
  const payments = useStore((s) => s.payments.filter((p) => p.studentId === id));
  const [receipt, setReceipt] = useState<Payment | null>(null);
  if (status === "ready" && !child) return <Alert variant="warning">No encontramos al estudiante solicitado.</Alert>;
  return (
    <>
      <PageHeader title={child?.name ?? "Estado de cuenta"} subtitle="Estado de cuenta" crumbs={[{ label: "Inicio", to: "/portal" }, { label: "Estado de cuenta" }]} />
      <LoadGate status={status} retry={retry}>
        {child ? (
          <div className="flex flex-col gap-4">
            <Card><p className="text-sm text-text-secondary">Saldo pendiente</p><p className="text-2xl font-semibold"><Amount value={child.balance} strong /></p></Card>
            <Card title="Cargos">
              {child.charges.length === 0 ? <EmptyState title="Sin cargos" description="Aún no hay cargos para este estudiante." action={{ label: "Volver al inicio", onAction: goHome }} /> : (
                <ResponsiveRows caption="Cargos" head={["Concepto", "Vence", "Estado", "Monto"]} rightFrom={[3]}
                  rows={child.charges.map((c) => ({ key: c.id, cells: [c.concept, <span className="tabular-nums">{c.due}</span>, <StatusFor status={c.status} />, <Amount value={c.amount} />] }))} />
              )}
            </Card>
            <Card title="Pagos">
              {payments.length === 0 ? <EmptyState title="Aún no hay pagos" description="Cuando se registre un pago aparecerá aquí." action={{ label: "Volver al inicio", onAction: goHome }} /> : (
                <ResponsiveRows caption="Pagos" head={["Recibo", "Fecha", "Estado", "Monto"]} rightFrom={[3]}
                  rows={payments.map((p) => ({ key: p.id, cells: [<button type="button" className="text-text-link hover:underline" onClick={() => setReceipt(p)}>{p.number}</button>, <span className="tabular-nums">{p.date}</span>, <StatusFor status={p.status} />, <Amount value={p.amount} />] }))} />
              )}
            </Card>
          </div>
        ) : null}
      </LoadGate>
      {receipt ? <Receipt_ payment={receipt} onClose={() => setReceipt(null)} /> : null}
    </>
  );
}

function Receipt_({ payment, onClose }: { payment: Payment; onClose: () => void }) {
  const student = useStore((s) => s.students.find((x) => x.id === payment.studentId));
  return (
    <Modal title={`Recibo ${payment.number}`} onClose={onClose} footer={<Button onClick={onClose}>Cerrar</Button>}>
      <dl className="flex flex-col gap-2 text-text-primary">
        <div className="flex justify-between gap-3"><dt className="text-text-secondary">Estudiante</dt><dd className="text-right">{student?.name}</dd></div>
        <div className="flex justify-between gap-3"><dt className="text-text-secondary">Fecha</dt><dd className="tabular-nums">{payment.date}</dd></div>
        <div className="flex justify-between gap-3"><dt className="text-text-secondary">Método</dt><dd>{payment.method}</dd></div>
        <div className="flex justify-between gap-3"><dt className="text-text-secondary">Conceptos</dt><dd className="text-right">{payment.concepts.join(", ")}</dd></div>
        <div className="flex justify-between gap-3"><dt className="text-text-secondary">Estado</dt><dd><StatusFor status={payment.status} /></dd></div>
        <div className="flex justify-between gap-3"><dt className="text-text-secondary">Monto</dt><dd><Amount value={payment.amount} strong /></dd></div>
      </dl>
    </Modal>
  );
}

export function PortalHistory() {
  const { status, retry } = useFakeLoad("portal-history");
  const children = useChildren();
  const all = useStore((s) => s.payments);
  const [childId, setChildId] = useState("");
  const [receipt, setReceipt] = useState<Payment | null>(null);
  const ids = children.map((c) => c.id);
  const mine = all.filter((p) => ids.includes(p.studentId));
  const rows = mine.filter((p) => childId === "" || p.studentId === childId);
  const nameOf = (sid: string) => children.find((c) => c.id === sid)?.name.split(" ")[0] ?? "";
  return (
    <>
      <PageHeader title="Historial de pagos" subtitle="Pagos registrados por el colegio y sus recibos." />
      <LoadGate status={status} retry={retry}>
        <Card>
          <div className="mb-4 flex max-w-xs flex-col gap-1">
            <label htmlFor="hijo" className="text-sm font-medium text-text-primary">Estudiante</label>
            <Select id="hijo" value={childId} onChange={(e) => setChildId(e.target.value)}><option value="">Todos</option>{children.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</Select>
          </div>
          {mine.length === 0 ? <EmptyState title="Aún no hay pagos" description="Cuando el colegio registre un pago aparecerá aquí." action={{ label: "Volver al inicio", onAction: goHome }} /> : rows.length === 0 ? (
            <EmptyState title="Sin resultados" description="Este estudiante no tiene pagos registrados." action={{ label: "Mostrar todos", onAction: () => setChildId("") }} />
          ) : (
            <ResponsiveRows caption="Historial de pagos" head={["Recibo", "Fecha", "Estudiante", "Estado", "Monto"]} rightFrom={[4]}
              rows={rows.map((p) => ({ key: p.id, cells: [<button type="button" className="text-text-link hover:underline" onClick={() => setReceipt(p)}>{p.number}</button>, <span className="tabular-nums">{p.date}</span>, nameOf(p.studentId), <StatusFor status={p.status} />, <Amount value={p.amount} />] }))} />
          )}
        </Card>
      </LoadGate>
      {receipt ? <Receipt_ payment={receipt} onClose={() => setReceipt(null)} /> : null}
    </>
  );
}

export function PortalReport() {
  const { status, retry } = useFakeLoad("portal-report");
  const children = useChildren();
  const reports = useStore((s) => s.paymentReports);
  const [f, setF] = useState({ studentId: "", reference: "", amount: "", date: "", attachment: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.studentId) err["student"] = "Seleccione al estudiante.";
    if (!f.reference.trim()) err["reference"] = "Escriba el número de referencia del pago.";
    if (!isTypedAmount(f.amount)) err["amount"] = "Escriba un monto como 2,350.00.";
    if (!/^\d{2}\/\d{2}\/\d{4}$/.test(f.date)) err["date"] = "Use el formato dd/mm/aaaa.";
    setErrors(err);
    if (Object.keys(err).length) return;
    const student = children.find((c) => c.id === f.studentId);
    actions.addPaymentReport({ id: nextId("rp"), studentId: f.studentId, student: student?.name ?? "", reference: f.reference.trim(), amount: displayTyped(f.amount), date: f.date, attachment: f.attachment.trim(), status: "Pendiente de revisión", sentOn: "07/11/2026 10:30" });
    notify("Reporte enviado. El colegio lo revisará.");
    setF({ studentId: "", reference: "", amount: "", date: "", attachment: "" });
  };
  return (
    <>
      <PageHeader title="Reportar un pago" subtitle="Si ya pagó por transferencia o depósito, avise al colegio. Un cajero lo verificará." />
      <form onSubmit={submit} noValidate>
        <Card>
          <div className="grid gap-5 sm:grid-cols-2">
            <FormField label="Estudiante" required error={errors["student"]} className="sm:col-span-2">{(c) => <Select {...c} value={f.studentId} onChange={(e) => setF({ ...f, studentId: e.target.value })}><option value="">Seleccione…</option>{children.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}</Select>}</FormField>
            <FormField label="Número de referencia" required error={errors["reference"]}>{(c) => <Input {...c} value={f.reference} onChange={(e) => setF({ ...f, reference: e.target.value })} />}</FormField>
            <FormField label="Monto pagado (L)" required error={errors["amount"]}>{(c) => <Input {...c} inputMode="decimal" className="text-right tabular-nums" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />}</FormField>
            <FormField label="Fecha del pago" required description="dd/mm/aaaa" error={errors["date"]}>{(c) => <Input {...c} className="tabular-nums" value={f.date} onChange={(e) => setF({ ...f, date: e.target.value })} />}</FormField>
            <FormField label="Comprobante (opcional)" description="En el prototipo solo se guarda el nombre del archivo.">{(c) => <Input {...c} placeholder="comprobante.jpg" value={f.attachment} onChange={(e) => setF({ ...f, attachment: e.target.value })} />}</FormField>
          </div>
          <div className="mt-5 flex justify-end"><Button type="submit">Enviar reporte</Button></div>
        </Card>
      </form>
      <h2 className="mt-8 mb-3 text-lg font-semibold text-text-primary">Mis reportes</h2>
      <LoadGate status={status} retry={retry}>
        <Card>
          {reports.length === 0 ? <EmptyState title="Aún no ha reportado pagos" description="Los reportes que envíe aparecerán aquí." action={{ label: "Volver al inicio", onAction: goHome }} /> : (
            <ResponsiveRows caption="Pagos reportados" head={["Enviado", "Estudiante", "Referencia", "Estado", "Monto"]} rightFrom={[4]}
              rows={reports.map((r) => ({ key: r.id, cells: [<span className="tabular-nums">{r.sentOn}</span>, r.student.split(" ")[0] ?? "", <span><span className="tabular-nums">{r.reference}</span>{r.attachment ? <span className="block text-xs text-text-secondary">{r.attachment}</span> : null}</span>, <Pill tone={REPORT_TONE[r.status]}>{r.status}</Pill>, <Amount value={r.amount} />] }))} />
          )}
        </Card>
      </LoadGate>
    </>
  );
}

export function PortalPrefs() {
  const { status, retry } = useFakeLoad("portal-prefs");
  const guardian = useStore((s) => s.guardians.find((g) => g.id === PORTAL_GUARDIAN_ID));
  const [channels, setChannels] = useState<string[]>(["Correo", "WhatsApp"]);
  const [topics, setTopics] = useState<string[]>(["Recordatorios de vencimiento", "Confirmación de pagos"]);
  const toggle = (list: string[], set: (v: string[]) => void, v: string) => set(list.includes(v) ? list.filter((x) => x !== v) : [...list, v]);
  return (
    <>
      <PageHeader title="Contacto y avisos" subtitle="Elija cómo desea que el colegio le contacte." />
      <LoadGate status={status} retry={retry}>
        <form onSubmit={(e) => { e.preventDefault(); notify("Preferencias guardadas."); }} className="flex flex-col gap-4">
          <Card title="Datos de contacto">
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField label="Correo electrónico">{(c) => <Input {...c} type="email" defaultValue={guardian?.email} />}</FormField>
              <FormField label="Teléfono">{(c) => <Input {...c} className="tabular-nums" defaultValue={guardian?.phone} />}</FormField>
            </div>
          </Card>
          <Card title="Canales de aviso">
            <fieldset className="flex flex-col gap-2"><legend className="sr-only">Canales de aviso</legend>
              {["Correo", "SMS", "WhatsApp"].map((c) => <label key={c} className="flex items-center gap-2 text-sm"><input type="checkbox" checked={channels.includes(c)} onChange={() => toggle(channels, setChannels, c)} /> {c}</label>)}
            </fieldset>
          </Card>
          <Card title="Qué avisos desea recibir">
            <fieldset className="flex flex-col gap-2"><legend className="sr-only">Avisos</legend>
              {["Recordatorios de vencimiento", "Confirmación de pagos", "Avisos generales del colegio"].map((c) => <label key={c} className="flex items-center gap-2 text-sm"><input type="checkbox" checked={topics.includes(c)} onChange={() => toggle(topics, setTopics, c)} /> {c}</label>)}
            </fieldset>
          </Card>
          <div className="flex justify-end"><Button type="submit">Guardar preferencias</Button></div>
        </form>
      </LoadGate>
    </>
  );
}
