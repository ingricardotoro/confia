import { Link, useNavigate } from "@tanstack/react-router";
import { Alert, Badge, Button } from "@confia/ui";
import { useState } from "react";
import { Amount, Card, DataTable, DetailList, LoadGate, Modal, PageHeader, Select, StatusFor } from "../lib/ui";
import type { Status } from "../mock/data";
import { actions, notify, useFakeLoad, useStore } from "../mock/store";

interface ChargeRow { id: string; studentId: string; student: string; concept: string; due: string; amount: string; status: Status }

export function ChargeList() {
  const { status, retry } = useFakeLoad("charges");
  const students = useStore((s) => s.students);
  const navigate = useNavigate();
  const [st, setSt] = useState("");
  const all: ChargeRow[] = students.flatMap((s) => s.charges.filter((c) => c.status !== "paid").map((c) => ({ id: c.id, studentId: s.id, student: s.name, concept: c.concept, due: c.due, amount: c.amount, status: c.status })));
  const rows = all.filter((r) => st === "" || r.status === st);
  return (
    <>
      <PageHeader title="Cargos y estado de cuenta" subtitle="Cargos pendientes de todos los estudiantes. Abra al estudiante para ver su estado de cuenta completo." />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Cargos pendientes" rows={rows} rowKey={(r) => r.id} searchPlaceholder="Buscar por estudiante o concepto"
          searchText={(r) => `${r.student} ${r.concept}`} filtersActive={st !== ""} onClearFilters={() => setSt("")}
          filters={<Select aria-label="Estado" value={st} onChange={(e) => setSt(e.target.value)} className="w-44"><option value="">Todos los estados</option><option value="overdue">Vencido</option><option value="pending">Pendiente</option></Select>}
          emptyTitle="No hay cargos pendientes" emptyDescription="Todos los estudiantes están al día." emptyAction={{ label: "Ir a estudiantes", onAction: () => void navigate({ to: "/estudiantes" }) }}
          columns={[
            { header: "Estudiante", render: (r) => <Link to="/estudiantes/$id" params={{ id: r.studentId }} className="font-medium text-text-link hover:underline">{r.student}</Link> },
            { header: "Concepto", render: (r) => r.concept },
            { header: "Vence", render: (r) => <span className="tabular-nums">{r.due}</span> },
            { header: "Estado", render: (r) => <StatusFor status={r.status} /> },
            { header: "Monto", align: "right", render: (r) => <Amount value={r.amount} /> },
          ]}
        />
      </LoadGate>
    </>
  );
}

export function CashPage() {
  const { status, retry } = useFakeLoad("cash");
  const sessions = useStore((s) => s.cashSessions);
  const current = sessions.find((s) => s.closed === null);
  const [confirm, setConfirm] = useState(false);
  return (
    <>
      <PageHeader title="Caja" subtitle="Apertura, cierre y arqueo del día."
        actions={current ? <Button onClick={() => setConfirm(true)}>Cerrar caja</Button> : <Button onClick={() => { actions.openCash({ id: `k${sessions.length + 1}x`, cashier: "Marlene Cáceres", opened: "07/11/2026 10:15", closed: null, opening: "L 1,000.00", collected: "L 0.00", cash: "L 0.00", transfers: "L 0.00", cards: "L 0.00", expected: "L 1,000.00", counted: "-", difference: "-" }); notify("Caja abierta."); }}>Abrir caja</Button>} />
      <LoadGate status={status} retry={retry}>
        {current ? (
          <Card title="Sesión de caja abierta" actions={<Badge size="sm" variant="primary">Abierta</Badge>} className="mb-6">
            <DetailList items={[["Cajero", current.cashier], ["Apertura", <span className="tabular-nums">{current.opened}</span>], ["Fondo inicial", <Amount value={current.opening} />], ["Total cobrado", <Amount value={current.collected} strong />], ["Efectivo", <Amount value={current.cash} />], ["Transferencias", <Amount value={current.transfers} />], ["Tarjetas", <Amount value={current.cards} />], ["Efectivo esperado en caja", <Amount value={current.expected} strong />]]} />
          </Card>
        ) : <div className="mb-6"><Alert variant="info" title="No hay caja abierta">Abra la caja para empezar a registrar cobros.</Alert></div>}
        <DataTable
          caption="Historial de cajas" rows={sessions} rowKey={(s) => s.id} searchPlaceholder="Buscar por cajero" searchText={(s) => s.cashier}
          emptyTitle="Sin sesiones de caja" emptyDescription="Abra la primera caja." emptyAction={{ label: "Abrir caja", onAction: () => undefined }}
          columns={[
            { header: "Cajero", render: (s) => s.cashier },
            { header: "Apertura", render: (s) => <span className="tabular-nums">{s.opened}</span> },
            { header: "Cierre", render: (s) => <span className="tabular-nums">{s.closed ?? "En curso"}</span> },
            { header: "Cobrado", align: "right", render: (s) => <Amount value={s.collected} /> },
            { header: "Contado", align: "right", render: (s) => <Amount value={s.counted} /> },
            { header: "Diferencia", align: "right", render: (s) => <Amount value={s.difference} strong={s.difference.startsWith("-") && s.difference !== "-"} /> },
          ]}
        />
      </LoadGate>
      {confirm && current ? (
        <Modal title="Cerrar caja" onClose={() => setConfirm(false)} footer={<><Button variant="secondary" onClick={() => setConfirm(false)}>Cancelar</Button><Button onClick={() => { actions.closeCash(current.id, "07/11/2026 17:00"); setConfirm(false); notify("Caja cerrada. Arqueo sin diferencias."); }}>Cerrar caja</Button></>}>
          Efectivo esperado: <Amount value={current.expected} strong />. En el prototipo el arqueo se registra sin diferencias.
        </Modal>
      ) : null}
    </>
  );
}

export function InvoiceList() {
  const { status, retry } = useFakeLoad("invoices");
  const invoices = useStore((s) => s.invoices);
  const navigate = useNavigate();
  return (
    <>
      <PageHeader title="Facturación" subtitle="Documentos fiscales emitidos con CAI vigente." />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Facturas" rows={invoices} rowKey={(f) => f.id} searchPlaceholder="Buscar por correlativo o cliente" searchText={(f) => `${f.correlative} ${f.client}`}
          emptyTitle="Aún no hay facturas" emptyDescription="Las facturas se emiten al registrar pagos." emptyAction={{ label: "Registrar pago", onAction: () => void navigate({ to: "/pagos/nuevo", search: {} }) }}
          columns={[
            { header: "Correlativo", render: (f) => <span className="font-medium tabular-nums">{f.correlative}</span> },
            { header: "CAI", render: (f) => <span className="text-xs tabular-nums text-text-secondary">{f.cai}</span> },
            { header: "Fecha", render: (f) => <span className="tabular-nums">{f.date}</span> },
            { header: "Cliente", render: (f) => f.client },
            { header: "Recibo", render: (f) => <Link to="/pagos/$id" params={{ id: f.paymentId }} className="text-text-link hover:underline">Ver recibo</Link> },
            { header: "Estado", render: (f) => <StatusFor status={f.status} /> },
            { header: "Total", align: "right", render: (f) => <Amount value={f.total} /> },
          ]}
        />
      </LoadGate>
    </>
  );
}
