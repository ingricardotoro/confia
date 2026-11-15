import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { Alert, Button, FormField, Input, Textarea } from "@confia/ui";
import { Pencil, Plus } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Amount, Card, DataTable, DetailList, Drawer, LoadGate, MiniTable, Modal, PageHeader, Pill, Select, displayTyped, isTypedAmount, type Tone } from "../lib/ui";
import {
  AUDIENCE_KINDS, BUCKETS, CHANNELS, PROMISE_STATUSES, RECIPIENTS_ALL, RECIPIENTS_BY_BUCKET, RECIPIENTS_GRADE, REMINDER_CHANNELS, TEMPLATES, debtors,
  type ContactStatus, type PaymentPromise, type PromiseStatus, type ReminderStatus,
} from "../mock/collections-data";
import { GRADES } from "../mock/data";
import { actions, nextId, notify, useFakeLoad, useStore } from "../mock/store";

const DATE = /^\d{2}\/\d{2}\/\d{4}$/;
const CONTACT_TONE: Record<ContactStatus, Tone> = { "Sin contacto": "neutral", Contactado: "outline", "Promesa vigente": "primary", Incumplida: "danger" };
const PROMISE_TONE: Record<PromiseStatus, Tone> = { Vigente: "primary", Cumplida: "success", Incumplida: "danger" };
const REMINDER_TONE: Record<ReminderStatus, Tone> = { Programado: "primary", Enviado: "success", Fallido: "danger" };

export function DebtorList() {
  const { status, retry } = useFakeLoad("debtors");
  const navigate = useNavigate();
  const [grade, setGrade] = useState("");
  const [bucket, setBucket] = useState("");
  const [openId, setOpenId] = useState<string | null>(null);
  const rows = debtors.filter((d) => (grade === "" || d.grade === grade) && (bucket === "" || d.bucket === bucket));
  const open = debtors.find((d) => d.id === openId);
  return (
    <>
      <PageHeader title="Morosos" subtitle="Estudiantes con cargos vencidos y el estado de su gestión de cobro." actions={<Button variant="secondary" onClick={() => void navigate({ to: "/cobranza/promesas/nueva" })}><Plus /> Registrar promesa</Button>} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Estudiantes morosos" rows={rows} rowKey={(d) => d.id} searchPlaceholder="Buscar por estudiante o encargado" searchText={(d) => `${d.student} ${d.guardian}`}
          filtersActive={grade !== "" || bucket !== ""} onClearFilters={() => { setGrade(""); setBucket(""); }}
          filters={<>
            <Select aria-label="Grado" value={grade} onChange={(e) => setGrade(e.target.value)} className="w-40"><option value="">Todos los grados</option>{GRADES.map((g) => <option key={g}>{g}</option>)}</Select>
            <Select aria-label="Tramo de mora" value={bucket} onChange={(e) => setBucket(e.target.value)} className="w-44"><option value="">Todos los tramos</option>{BUCKETS.map((b) => <option key={b}>{b}</option>)}</Select>
          </>}
          emptyTitle="No hay morosos" emptyDescription="Ningún estudiante tiene cargos vencidos." emptyAction={{ label: "Ir a pagos", onAction: () => void navigate({ to: "/pagos" }) }}
          columns={[
            { header: "Estudiante", render: (d) => <button type="button" onClick={() => setOpenId(d.id)} className="text-left font-medium text-text-link hover:underline">{d.student}</button> },
            { header: "Grado", render: (d) => d.grade },
            { header: "Encargado", render: (d) => d.guardian },
            { header: "Mora", render: (d) => <span className="tabular-nums">{d.days}</span> },
            { header: "Vencido", align: "right", render: (d) => <Amount value={d.overdue} strong /> },
            { header: "Último contacto", render: (d) => <span className="tabular-nums">{d.lastContact}</span> },
            { header: "Gestión", render: (d) => <Pill tone={CONTACT_TONE[d.status]}>{d.status}</Pill> },
          ]}
        />
      </LoadGate>
      {open ? (
        <Drawer title={open.student} onClose={() => setOpenId(null)}>
          <div className="flex flex-col gap-5">
            <DetailList items={[["Grado", open.grade], ["Encargado", open.guardian], ["Teléfono", <span className="tabular-nums">{open.phone}</span>], ["Gestión", <Pill tone={CONTACT_TONE[open.status]}>{open.status}</Pill>], ["Mora máxima", open.days], ["Total vencido", <Amount value={open.overdue} strong />]]} />
            <Card title="Cargos vencidos">
              <MiniTable caption="Cargos vencidos" head={["Concepto", "Vence", "Mora", "Monto"]} rightFrom={[3]}
                rows={open.charges.map((c) => [c.concept, <span className="tabular-nums">{c.due}</span>, <span className="tabular-nums">{c.days}</span>, <Amount value={c.amount} />])} />
            </Card>
            <Card title="Historial de contacto">
              {open.history.length === 0 ? <p className="text-sm text-text-secondary">Aún no se ha contactado a este encargado.</p> : (
                <ul className="flex flex-col gap-3 text-sm">
                  {open.history.map((h) => <li key={h.at} className="border-l-2 border-border-subtle pl-3"><p className="text-text-secondary"><span className="tabular-nums">{h.at}</span> · {h.channel} · {h.by}</p><p className="text-text-primary">{h.note}</p></li>)}
                </ul>
              )}
            </Card>
            <div className="flex flex-wrap gap-2">
              <Link to="/estudiantes/$id" params={{ id: open.studentId }}><Button variant="secondary">Ver estudiante</Button></Link>
              <Link to="/cobranza/promesas/nueva"><Button>Registrar promesa de pago</Button></Link>
            </div>
          </div>
        </Drawer>
      ) : null}
    </>
  );
}

export function PromiseList() {
  const { status, retry } = useFakeLoad("promises");
  const promises = useStore((s) => s.promises);
  const navigate = useNavigate();
  const [st, setSt] = useState("");
  const [pending, setPending] = useState<{ p: PaymentPromise; to: "Cumplida" | "Incumplida" } | null>(null);
  const rows = promises.filter((p) => st === "" || p.status === st);
  const goNew = () => void navigate({ to: "/cobranza/promesas/nueva" });
  return (
    <>
      <PageHeader title="Promesas de pago" subtitle="Compromisos de pago acordados con los encargados." actions={<Button onClick={goNew}><Plus /> Nueva promesa</Button>} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Promesas de pago" rows={rows} rowKey={(p) => p.id} searchPlaceholder="Buscar por estudiante o encargado" searchText={(p) => `${p.student} ${p.guardian}`}
          filtersActive={st !== ""} onClearFilters={() => setSt("")}
          filters={<Select aria-label="Estado" value={st} onChange={(e) => setSt(e.target.value)} className="w-44"><option value="">Todos los estados</option>{PROMISE_STATUSES.map((x) => <option key={x}>{x}</option>)}</Select>}
          emptyTitle="Aún no hay promesas de pago" emptyDescription="Registre la primera promesa acordada con un encargado." emptyAction={{ label: "Nueva promesa", onAction: goNew }}
          columns={[
            { header: "Estudiante", render: (p) => <span className="font-medium">{p.student}</span> },
            { header: "Encargado", render: (p) => p.guardian },
            { header: "Fecha prometida", render: (p) => <span className="tabular-nums">{p.date}</span> },
            { header: "Monto", align: "right", render: (p) => <Amount value={p.amount} strong /> },
            { header: "Canal", render: (p) => p.channel },
            { header: "Estado", render: (p) => <Pill tone={PROMISE_TONE[p.status]}>{p.status}</Pill> },
            { header: "Acciones", render: (p) => (
              <div className="flex flex-wrap gap-1">
                <Link to="/cobranza/promesas/$id/editar" params={{ id: p.id }}><Button variant="subtle" size="sm"><Pencil /> Editar</Button></Link>
                {p.status === "Vigente" ? <>
                  <Button variant="secondary" size="sm" onClick={() => setPending({ p, to: "Cumplida" })}>Cumplida</Button>
                  <Button variant="secondary" size="sm" onClick={() => setPending({ p, to: "Incumplida" })}>Incumplida</Button>
                </> : null}
              </div>
            ) },
          ]}
        />
      </LoadGate>
      {pending ? (
        <Modal title={`Marcar la promesa como ${pending.to.toLowerCase()}`} onClose={() => setPending(null)}
          footer={<><Button variant="secondary" onClick={() => setPending(null)}>Cancelar</Button><Button onClick={() => { actions.setPromiseStatus(pending.p.id, pending.to); notify(`Promesa marcada como ${pending.to.toLowerCase()}.`); setPending(null); }}>Confirmar</Button></>}>
          Promesa de {pending.p.student} por <Amount value={pending.p.amount} strong /> para el {pending.p.date}. Esta acción cambia el estado de la gestión de cobro.
        </Modal>
      ) : null}
    </>
  );
}

export function PromiseForm({ mode }: { mode: "new" | "edit" }) {
  const { id } = useParams({ strict: false }) as { id?: string };
  const existing = useStore((s) => s.promises.find((x) => x.id === id));
  const students = useStore((s) => s.students);
  const guardians = useStore((s) => s.guardians);
  const navigate = useNavigate();
  const [f, setF] = useState<PaymentPromise>(existing ?? { id: "", studentId: "", student: "", guardian: "", date: "", amount: "", channel: CHANNELS[0], notes: "", status: "Vigente" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const pick = (sid: string) => {
    const s = students.find((x) => x.id === sid);
    const g = guardians.find((x) => s?.guardianIds[0] === x.id);
    setF({ ...f, studentId: sid, student: s?.name ?? "", guardian: g?.name ?? "" });
  };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.studentId) err["student"] = "Seleccione al estudiante.";
    if (!DATE.test(f.date)) err["date"] = "Use el formato dd/mm/aaaa.";
    if (!isTypedAmount(f.amount)) err["amount"] = "Escriba un monto como 2,350.00.";
    setErrors(err);
    if (Object.keys(err).length) return;
    actions.savePromise({ ...f, amount: displayTyped(f.amount), id: f.id || nextId("pr") });
    notify(mode === "new" ? "Promesa registrada." : "Cambios guardados.");
    void navigate({ to: "/cobranza/promesas" });
  };
  if (mode === "edit" && !existing) return <Alert variant="warning">No encontramos la promesa solicitada.</Alert>;
  return (
    <>
      <PageHeader title={mode === "new" ? "Nueva promesa de pago" : "Editar promesa de pago"} crumbs={[{ label: "Promesas de pago", to: "/cobranza/promesas" }, { label: mode === "new" ? "Nueva" : "Editar" }]} />
      <form onSubmit={submit} noValidate>
        <Card>
          <div className="grid gap-5 sm:grid-cols-2">
            <FormField label="Estudiante" required error={errors["student"]} className="sm:col-span-2">{(c) => <Select {...c} value={f.studentId} onChange={(e) => pick(e.target.value)}><option value="">Seleccione…</option>{students.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.grade})</option>)}</Select>}</FormField>
            <FormField label="Encargado" description="Se completa según el estudiante.">{(c) => <Input {...c} readOnly value={f.guardian} />}</FormField>
            <FormField label="Canal del acuerdo">{(c) => <Select {...c} value={f.channel} onChange={(e) => setF({ ...f, channel: e.target.value })}>{CHANNELS.map((x) => <option key={x}>{x}</option>)}</Select>}</FormField>
            <FormField label="Fecha prometida" required description="dd/mm/aaaa" error={errors["date"]}>{(c) => <Input {...c} className="tabular-nums" value={f.date} onChange={(e) => setF({ ...f, date: e.target.value })} />}</FormField>
            <FormField label="Monto prometido (L)" required error={errors["amount"]}>{(c) => <Input {...c} inputMode="decimal" className="text-right tabular-nums" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />}</FormField>
            <FormField label="Estado">{(c) => <Select {...c} value={f.status} onChange={(e) => setF({ ...f, status: e.target.value as PromiseStatus })}>{PROMISE_STATUSES.map((x) => <option key={x}>{x}</option>)}</Select>}</FormField>
            <FormField label="Notas" className="sm:col-span-2">{(c) => <Textarea {...c} rows={3} value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} />}</FormField>
          </div>
        </Card>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => void navigate({ to: "/cobranza/promesas" })}>Cancelar</Button>
          <Button type="submit">Guardar</Button>
        </div>
      </form>
    </>
  );
}

export function ReminderList() {
  const { status, retry } = useFakeLoad("reminders");
  const reminders = useStore((s) => s.reminders);
  const navigate = useNavigate();
  const [st, setSt] = useState("");
  const rows = reminders.filter((r) => st === "" || r.status === st);
  const goNew = () => void navigate({ to: "/cobranza/recordatorios/nuevo" });
  return (
    <>
      <PageHeader title="Recordatorios" subtitle="Avisos programados y enviados a los encargados." actions={<Button onClick={goNew}><Plus /> Nuevo recordatorio</Button>} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Recordatorios" rows={rows} rowKey={(r) => r.id} searchPlaceholder="Buscar por plantilla o audiencia" searchText={(r) => `${r.template} ${r.audience}`}
          filtersActive={st !== ""} onClearFilters={() => setSt("")}
          filters={<Select aria-label="Estado" value={st} onChange={(e) => setSt(e.target.value)} className="w-44"><option value="">Todos los estados</option><option>Programado</option><option>Enviado</option><option>Fallido</option></Select>}
          emptyTitle="Aún no hay recordatorios" emptyDescription="Programe el primer aviso a los encargados." emptyAction={{ label: "Nuevo recordatorio", onAction: goNew }}
          columns={[
            { header: "Fecha", render: (r) => <span className="tabular-nums">{r.scheduled}</span> },
            { header: "Canal", render: (r) => r.channel },
            { header: "Plantilla", render: (r) => <span className="font-medium">{r.template}</span> },
            { header: "Audiencia", render: (r) => r.audience },
            { header: "Destinatarios", align: "right", render: (r) => <span className="tabular-nums">{r.recipients}</span> },
            { header: "Estado", render: (r) => <Pill tone={REMINDER_TONE[r.status]}>{r.status}</Pill> },
          ]}
        />
      </LoadGate>
    </>
  );
}

export function ReminderForm() {
  const navigate = useNavigate();
  const [kind, setKind] = useState(AUDIENCE_KINDS[0]!);
  const [bucket, setBucket] = useState<string>(BUCKETS[0]);
  const [grade, setGrade] = useState(GRADES[0]!);
  const [template, setTemplate] = useState(TEMPLATES[0]!);
  const [channel, setChannel] = useState(REMINDER_CHANNELS[0]!);
  const [when, setWhen] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const audience = kind === "Por tramo de mora" ? `Tramo ${bucket}` : kind === "Por grado" ? `Grado ${grade}` : "Todos los morosos";
  const recipients = kind === "Por tramo de mora" ? RECIPIENTS_BY_BUCKET[bucket] ?? "0" : kind === "Por grado" ? RECIPIENTS_GRADE : RECIPIENTS_ALL;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!/^\d{2}\/\d{2}\/\d{4}( \d{2}:\d{2})?$/.test(when)) err["when"] = "Use el formato dd/mm/aaaa o dd/mm/aaaa hh:mm.";
    setErrors(err);
    if (Object.keys(err).length) return;
    actions.addReminder({ id: nextId("r"), channel, template, audience, recipients, scheduled: when.length === 10 ? `${when} 08:00` : when, status: "Programado" });
    notify("Recordatorio programado.");
    void navigate({ to: "/cobranza/recordatorios" });
  };
  return (
    <>
      <PageHeader title="Nuevo recordatorio" crumbs={[{ label: "Recordatorios", to: "/cobranza/recordatorios" }, { label: "Nuevo" }]} />
      <form onSubmit={submit} noValidate>
        <Card>
          <div className="grid gap-5 sm:grid-cols-2">
            <FormField label="Audiencia">{(c) => <Select {...c} value={kind} onChange={(e) => setKind(e.target.value)}>{AUDIENCE_KINDS.map((x) => <option key={x}>{x}</option>)}</Select>}</FormField>
            {kind === "Por tramo de mora" ? <FormField label="Tramo de mora">{(c) => <Select {...c} value={bucket} onChange={(e) => setBucket(e.target.value)}>{BUCKETS.map((x) => <option key={x}>{x}</option>)}</Select>}</FormField> : null}
            {kind === "Por grado" ? <FormField label="Grado">{(c) => <Select {...c} value={grade} onChange={(e) => setGrade(e.target.value)}>{GRADES.map((x) => <option key={x}>{x}</option>)}</Select>}</FormField> : null}
            <FormField label="Plantilla">{(c) => <Select {...c} value={template} onChange={(e) => setTemplate(e.target.value)}>{TEMPLATES.map((x) => <option key={x}>{x}</option>)}</Select>}</FormField>
            <FormField label="Canal">{(c) => <Select {...c} value={channel} onChange={(e) => setChannel(e.target.value)}>{REMINDER_CHANNELS.map((x) => <option key={x}>{x}</option>)}</Select>}</FormField>
            <FormField label="Fecha programada" required description="dd/mm/aaaa (8:00 a. m. si no indica hora)" error={errors["when"]}>{(c) => <Input {...c} className="tabular-nums" value={when} onChange={(e) => setWhen(e.target.value)} />}</FormField>
          </div>
          <div className="mt-5"><Alert variant="info">Se enviará a {recipients} destinatario(s): {audience}. El número final lo confirma el servidor al enviar.</Alert></div>
        </Card>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => void navigate({ to: "/cobranza/recordatorios" })}>Cancelar</Button>
          <Button type="submit">Programar</Button>
        </div>
      </form>
    </>
  );
}
