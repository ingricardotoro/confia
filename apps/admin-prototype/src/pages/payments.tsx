import { Link, useNavigate, useParams, useSearch } from "@tanstack/react-router";
import { Alert, Badge, Button, EmptyState, FormField, Input } from "@confia/ui";
import { CheckCircle2, Plus, Printer } from "lucide-react";
import { useState } from "react";
import { Amount, Card, DataTable, DetailList, LoadGate, Modal, PageHeader, Select, StatusFor, displayTyped, isTypedAmount } from "../lib/ui";
import { CURRENT_USER, INSTITUTION, PAYMENT_METHODS, type Payment } from "../mock/data";
import { actions, notify, useFakeLoad, useStore } from "../mock/store";

export function PaymentList() {
  const { status, retry } = useFakeLoad("payments");
  const payments = useStore((s) => s.payments);
  const students = useStore((s) => s.students);
  const navigate = useNavigate();
  const [method, setMethod] = useState("");
  const [st, setSt] = useState("");
  const nameOf = (id: string) => students.find((s) => s.id === id)?.name ?? "-";
  const rows = payments.filter((p) => (method === "" || p.method === method) && (st === "" || p.status === st));
  const goNew = () => void navigate({ to: "/pagos/nuevo", search: {} });
  return (
    <>
      <PageHeader title="Pagos" subtitle="Cobros registrados en caja." actions={<Button onClick={goNew}><Plus /> Registrar pago</Button>} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Pagos" rows={rows} rowKey={(p) => p.id} searchPlaceholder="Buscar por recibo, estudiante o referencia"
          searchText={(p) => `${p.number} ${nameOf(p.studentId)} ${p.reference}`} filtersActive={method !== "" || st !== ""} onClearFilters={() => { setMethod(""); setSt(""); }}
          filters={
            <>
              <Select aria-label="Método de pago" value={method} onChange={(e) => setMethod(e.target.value)} className="w-52"><option value="">Todos los métodos</option>{PAYMENT_METHODS.map((m) => <option key={m}>{m}</option>)}</Select>
              <Select aria-label="Estado" value={st} onChange={(e) => setSt(e.target.value)} className="w-40"><option value="">Todos</option><option value="paid">Pagado</option><option value="partial">Parcial</option><option value="void">Anulado</option></Select>
            </>
          }
          emptyTitle="Aún no hay pagos" emptyDescription="Registre el primer pago para verlo aquí." emptyAction={{ label: "Registrar pago", onAction: goNew }}
          columns={[
            { header: "Recibo", render: (p) => <Link to="/pagos/$id" params={{ id: p.id }} className="font-medium text-text-link hover:underline">{p.number}</Link> },
            { header: "Fecha", render: (p) => <span className="tabular-nums">{p.date}</span> },
            { header: "Estudiante", render: (p) => nameOf(p.studentId) },
            { header: "Método", render: (p) => p.method },
            { header: "Estado", render: (p) => <StatusFor status={p.status} /> },
            { header: "Monto", align: "right", render: (p) => <Amount value={p.amount} /> },
          ]}
        />
      </LoadGate>
    </>
  );
}

const STEPS = ["Estudiante", "Cargos y monto", "Confirmación"];

export function PaymentWizard() {
  const search = useSearch({ strict: false }) as { student?: string };
  const { status, retry } = useFakeLoad("payment-new", 300);
  const students = useStore((s) => s.students);
  const fees = useStore((s) => s.fees);
  const feeOf = (concept: string) => fees.find((f) => concept.startsWith(f.name))?.name;
  const payments = useStore((s) => s.payments);
  const navigate = useNavigate();
  const [step, setStep] = useState(search.student ? 1 : 0);
  const [studentId, setStudentId] = useState(search.student ?? "");
  const [picked, setPicked] = useState<string[]>([]);
  const [amountText, setAmountText] = useState("");
  const [method, setMethod] = useState(PAYMENT_METHODS[0]!);
  const [reference, setReference] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [sending, setSending] = useState(false);

  const student = students.find((s) => s.id === studentId);
  const open = student?.charges.filter((c) => c.status !== "paid") ?? [];
  const chosen = open.filter((c) => picked.includes(c.id));

  const next0 = () => {
    if (!student) return setErrors({ student: "Seleccione al estudiante que paga." });
    setErrors({});
    setStep(1);
  };
  const next1 = () => {
    const err: Record<string, string> = {};
    if (chosen.length === 0) err["charges"] = "Seleccione al menos un cargo que cubre este pago.";
    if (!isTypedAmount(amountText)) err["amount"] = "Escriba un monto mayor que cero, por ejemplo 2350.00.";
    if (method !== "Efectivo" && !reference.trim()) err["reference"] = "Este método requiere un número de referencia.";
    setErrors(err);
    if (Object.keys(err).length === 0) setStep(2);
  };
  const confirm = () => {
    if (!student || sending) return;
    setSending(true);
    setTimeout(() => {
      const num = `REC-${String(231 + payments.length - 9).padStart(6, "0")}`;
      const p: Payment = {
        id: `p${num.slice(-3)}x`, number: num, studentId: student.id, date: "07/11/2026 10:05", method, reference: reference.trim() || "-",
        amount: displayTyped(amountText), status: chosen.length === open.length ? "paid" : "partial", cashier: CURRENT_USER.name, concepts: chosen.map((c) => c.concept),
      };
      actions.registerPayment(p, chosen.map((c) => c.id));
      notify(`Pago ${num} registrado.`);
      void navigate({ to: "/pagos/$id", params: { id: p.id } });
    }, 800);
  };

  return (
    <>
      <PageHeader title="Registrar pago" crumbs={[{ label: "Pagos", to: "/pagos" }, { label: "Registrar" }]} />
      <LoadGate status={status} retry={retry}>
        <ol className="mb-6 flex gap-2" aria-label="Pasos">
          {STEPS.map((s, i) => (
            <li key={s} aria-current={i === step ? "step" : undefined} className={`flex-1 rounded-md border px-3 py-2 text-sm ${i === step ? "border-primary bg-primary-soft-bg font-medium text-primary-soft-fg" : i < step ? "border-status-paid-border bg-status-paid-bg text-status-paid-fg" : "border-border-subtle text-text-secondary"}`}>
              {i + 1}. {s}
            </li>
          ))}
        </ol>

        {step === 0 ? (
          <Card title="¿Quién paga?">
            <div className="max-w-md">
              <FormField label="Estudiante" required error={errors["student"]}>
                {(c) => (
                  <Select {...c} value={studentId} onChange={(e) => { setStudentId(e.target.value); setPicked([]); }}>
                    <option value="">Seleccione un estudiante</option>
                    {students.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.grade} {s.section})</option>)}
                  </Select>
                )}
              </FormField>
              {student ? <p className="mt-3 text-sm text-text-secondary">Saldo pendiente: <Amount value={student.balance} strong /></p> : null}
            </div>
            <div className="mt-6 flex justify-end"><Button onClick={next0}>Continuar</Button></div>
          </Card>
        ) : null}

        {step === 1 && student ? (
          <Card title={`Cargos pendientes de ${student.name}`}>
            {open.length === 0 ? (
              <EmptyState title="Sin cargos pendientes" description="Este estudiante está al día. No hay nada que cobrar." action={{ label: "Elegir otro estudiante", onAction: () => setStep(0) }} />
            ) : (
              <>
                <fieldset className="mb-6">
                  <legend className="mb-2 text-sm font-medium text-text-primary">Cargos que cubre este pago</legend>
                  <ul className="divide-y divide-border-subtle rounded-md border border-border-subtle">
                    {open.map((c) => (
                      <li key={c.id}>
                        <label className="flex cursor-pointer items-center gap-3 px-3 py-2 text-sm">
                          <input type="checkbox" checked={picked.includes(c.id)} onChange={() => setPicked(picked.includes(c.id) ? picked.filter((x) => x !== c.id) : [...picked, c.id])} />
                          <span className="flex-1">{c.concept} <span className="text-text-secondary">· vence {c.due}</span>{feeOf(c.concept) ? <Badge size="sm" variant="outline" className="ml-2">Concepto: {feeOf(c.concept)}</Badge> : null}</span>
                          <StatusFor status={c.status} />
                          <Amount value={c.amount} />
                        </label>
                      </li>
                    ))}
                  </ul>
                  {errors["charges"] ? <p role="alert" className="mt-2 text-sm text-status-overdue-fg">{errors["charges"]}</p> : null}
                </fieldset>
                <div className="grid gap-5 sm:grid-cols-3">
                  <FormField label="Monto recibido (L)" required description="Escriba el monto tal como lo entrega el encargado." error={errors["amount"]}>{(c) => <Input {...c} inputMode="decimal" className="text-right tabular-nums" value={amountText} onChange={(e) => setAmountText(e.target.value)} />}</FormField>
                  <FormField label="Método de pago">{(c) => <Select {...c} value={method} onChange={(e) => setMethod(e.target.value)}>{PAYMENT_METHODS.map((m) => <option key={m}>{m}</option>)}</Select>}</FormField>
                  <FormField label="Referencia" required={method !== "Efectivo"} description={method === "Efectivo" ? "No aplica para efectivo." : "Número de transferencia, autorización o cheque."} error={errors["reference"]} disabled={method === "Efectivo"}>{(c) => <Input {...c} value={reference} onChange={(e) => setReference(e.target.value)} />}</FormField>
                </div>
              </>
            )}
            <div className="mt-6 flex justify-between"><Button variant="secondary" onClick={() => setStep(0)}>Atrás</Button>{open.length > 0 ? <Button onClick={next1}>Revisar pago</Button> : null}</div>
          </Card>
        ) : null}

        {step === 2 && student ? (
          <Card title="Confirme el pago">
            <Alert variant="warning" title="Revise antes de confirmar">Al confirmar se emite el recibo. Un pago registrado no se edita: solo se puede anular con un asiento nuevo.</Alert>
            <div className="mt-5"><DetailList items={[
              ["Estudiante", student.name], ["Grado", `${student.grade} ${student.section}`],
              ["Método de pago", method], ["Referencia", reference.trim() || "-"],
              ["Cargos cubiertos", <ul className="list-disc pl-5">{chosen.map((c) => <li key={c.id}>{c.concept} (<Amount value={c.amount} />)</li>)}</ul>],
              ["Saldo pendiente actual", <Amount value={student.balance} />],
              ["Monto a registrar", <Amount value={displayTyped(amountText)} strong />],
            ]} /></div>
            <div className="mt-6 flex justify-between">
              <Button variant="secondary" disabled={sending} onClick={() => setStep(1)}>Atrás</Button>
              <Button loading={sending} onClick={confirm}><CheckCircle2 /> Confirmar y emitir recibo</Button>
            </div>
          </Card>
        ) : null}
      </LoadGate>
    </>
  );
}

export function PaymentDetail() {
  const { id } = useParams({ strict: false }) as { id: string };
  const { status, retry } = useFakeLoad(`payment-${id}`);
  const payment = useStore((s) => s.payments.find((p) => p.id === id));
  const student = useStore((s) => s.students.find((x) => x.id === payment?.studentId));
  const [confirm, setConfirm] = useState(false);
  return (
    <LoadGate status={status} retry={retry}>
      {!payment ? (
        <Alert variant="warning" title="Pago no encontrado">No existe un pago con ese identificador.</Alert>
      ) : (
        <>
          <PageHeader title={`Recibo ${payment.number}`} crumbs={[{ label: "Pagos", to: "/pagos" }, { label: payment.number }]}
            actions={
              <>
                <Button variant="secondary" onClick={() => window.print()}><Printer /> Imprimir</Button>
                {payment.status !== "void" ? <Button variant="destructive" onClick={() => setConfirm(true)}>Anular pago</Button> : null}
              </>
            } />
          {payment.status === "void" ? <div className="mb-4"><Alert variant="destructive" title="Pago anulado">Este recibo fue anulado y ya no cuenta para el saldo.</Alert></div> : null}
          <div className="mx-auto max-w-xl rounded-lg border border-border-subtle bg-bg-surface p-8 shadow-md">
            <div className="mb-6 border-b border-dashed border-border-interactive pb-4 text-center">
              <p className="text-lg font-semibold text-text-primary">{INSTITUTION}</p>
              <p className="text-sm text-text-secondary">RTN 08019999123456 · Tegucigalpa, Honduras</p>
              <p className="mt-2 text-base font-semibold">Recibo de cobro {payment.number}</p>
              <div className="mt-1"><StatusFor status={payment.status} /></div>
            </div>
            <DetailList items={[
              ["Fecha", <span className="tabular-nums">{payment.date}</span>], ["Cajero", payment.cashier],
              ["Estudiante", student?.name ?? "-"], ["Método", payment.method], ["Referencia", payment.reference],
              ["Conceptos", payment.concepts.join(", ")],
            ]} />
            <div className="mt-6 flex items-center justify-between border-t border-dashed border-border-interactive pt-4">
              <span className="text-base font-semibold">Total recibido</span>
              <span className="text-xl"><Amount value={payment.amount} strong /></span>
            </div>
            <p className="mt-6 text-center text-xs text-text-tertiary">Documento de demostración. No tiene validez fiscal.</p>
          </div>
          {confirm ? (
            <Modal title="Anular pago" onClose={() => setConfirm(false)}
              footer={<><Button variant="secondary" onClick={() => setConfirm(false)}>Cancelar</Button><Button variant="destructive" onClick={() => { actions.voidPayment(payment.id); setConfirm(false); notify(`Pago ${payment.number} anulado.`); }}>Anular pago</Button></>}>
              Se anulará el recibo {payment.number}. Esta acción queda registrada en la bitácora y no se puede deshacer.
            </Modal>
          ) : null}
        </>
      )}
    </LoadGate>
  );
}
