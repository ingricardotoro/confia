import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { Alert, Badge, Button, FormField, Input } from "@confia/ui";
import { Pencil, Plus } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Amount, Card, DataTable, LoadGate, PageHeader, Select, displayTyped } from "../lib/ui";
import { FREQUENCIES, type Fee, type Frequency, type Scholarship } from "../mock/config-data";
import { actions, nextId, notify, useFakeLoad, useStore } from "../mock/store";

// Pattern-only validation: no value is ever parsed into a number.
const AMOUNT = /^\s*(L\s*)?\d+(,\d{3})*(\.\d{1,2})?\s*$/i;
const DATE = /^\d{2}\/\d{2}\/\d{4}$/;
const ActiveBadge = ({ on }: { on: boolean }) => <Badge size="sm" variant={on ? "primary" : "neutral"}>{on ? "Activo" : "Inactivo"}</Badge>;

export function FeeList() {
  const { status, retry } = useFakeLoad("fees");
  const fees = useStore((s) => s.fees);
  const navigate = useNavigate();
  const [freq, setFreq] = useState("");
  const [on, setOn] = useState("");
  const rows = fees.filter((f) => (freq === "" || f.frequency === freq) && (on === "" || (on === "on") === f.active));
  const goNew = () => void navigate({ to: "/configuracion/tarifas/nueva" });
  return (
    <>
      <PageHeader title="Tarifas y conceptos de cobro" subtitle="Conceptos que generan cargos y su monto por grado." actions={<Button onClick={goNew}><Plus /> Nuevo concepto</Button>} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Conceptos de cobro" rows={rows} rowKey={(f) => f.id} searchPlaceholder="Buscar por concepto" searchText={(f) => f.name}
          filtersActive={freq !== "" || on !== ""} onClearFilters={() => { setFreq(""); setOn(""); }}
          filters={
            <>
              <Select aria-label="Frecuencia" value={freq} onChange={(e) => setFreq(e.target.value)} className="w-44"><option value="">Toda frecuencia</option>{FREQUENCIES.map((x) => <option key={x}>{x}</option>)}</Select>
              <Select aria-label="Estado" value={on} onChange={(e) => setOn(e.target.value)} className="w-40"><option value="">Todos</option><option value="on">Activos</option><option value="off">Inactivos</option></Select>
            </>
          }
          emptyTitle="Aún no hay conceptos de cobro" emptyDescription="Cree el primer concepto, por ejemplo la mensualidad." emptyAction={{ label: "Nuevo concepto", onAction: goNew }}
          columns={[
            { header: "Concepto", render: (f) => <span className="font-medium">{f.name}</span> },
            { header: "Frecuencia", render: (f) => f.frequency },
            { header: "Vencimiento", render: (f) => f.dueDay },
            { header: "Política de mora", render: (f) => f.lateFee },
            {
              header: "Monto",
              align: "right",
              render: (f) => {
                const first = f.amounts[0]?.amount ?? "-";
                const same = f.amounts.every((a) => a.amount === first);
                return same ? <Amount value={first} /> : (
                  <details className="text-right">
                    <summary className="cursor-pointer text-text-link">Varía por grado</summary>
                    <ul className="mt-1 text-xs text-text-secondary">{f.amounts.map((a) => <li key={a.grade}>{a.grade}: <Amount value={a.amount} /></li>)}</ul>
                  </details>
                );
              },
            },
            { header: "Estado", render: (f) => <ActiveBadge on={f.active} /> },
            { header: "Acciones", render: (f) => <Link to="/configuracion/tarifas/$id/editar" params={{ id: f.id }}><Button variant="subtle" size="sm"><Pencil /> Editar</Button></Link> },
          ]}
        />
      </LoadGate>
    </>
  );
}

export function FeeForm({ mode }: { mode: "new" | "edit" }) {
  const { id } = useParams({ strict: false }) as { id?: string };
  const existing = useStore((s) => s.fees.find((x) => x.id === id));
  const grades = useStore((s) => s.grades);
  const navigate = useNavigate();
  const [f, setF] = useState<Fee>(existing ?? { id: "", name: "", frequency: "Mensual", dueDay: "", lateFee: "Sin mora", active: true, amounts: [] });
  // Amounts are typed text keyed by grade name.
  const [typed, setTyped] = useState<Record<string, string>>(() => Object.fromEntries(grades.map((g) => [g.name, f.amounts.find((a) => a.grade === g.name)?.amount ?? ""])));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.name.trim()) err["name"] = "Escriba el nombre del concepto.";
    if (!f.dueDay.trim()) err["dueDay"] = "Indique cuándo vence.";
    grades.forEach((g) => {
      const t = typed[g.name] ?? "";
      if (t.trim() !== "" && !AMOUNT.test(t)) err[g.id] = "Escriba un monto como 1250.00.";
    });
    if (grades.every((g) => (typed[g.name] ?? "").trim() === "")) err["amounts"] = "Indique el monto de al menos un grado.";
    setErrors(err);
    if (Object.keys(err).length) return;
    const amounts = grades.map((g) => ({ grade: g.name, amount: (typed[g.name] ?? "").trim() === "" ? "-" : displayTyped(typed[g.name]!) }));
    actions.saveFee({ ...f, id: f.id || nextId("t"), amounts });
    notify(mode === "new" ? "Concepto creado." : "Cambios guardados.");
    void navigate({ to: "/configuracion/tarifas" });
  };
  const setAll = (t: string) => setTyped(Object.fromEntries(grades.map((g) => [g.name, t])));
  if (mode === "edit" && !existing) return <Alert variant="warning">No encontramos el concepto solicitado.</Alert>;
  return (
    <>
      <PageHeader title={mode === "new" ? "Nuevo concepto de cobro" : `Editar ${existing?.name ?? ""}`} crumbs={[{ label: "Tarifas", to: "/configuracion/tarifas" }, { label: mode === "new" ? "Nuevo" : "Editar" }]} />
      <form onSubmit={submit} noValidate>
        <Card title="Datos del concepto">
          <div className="grid gap-5 sm:grid-cols-2">
            <FormField label="Nombre" required error={errors["name"]}>{(c) => <Input {...c} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />}</FormField>
            <FormField label="Frecuencia">{(c) => <Select {...c} value={f.frequency} onChange={(e) => setF({ ...f, frequency: e.target.value as Frequency })}>{FREQUENCIES.map((x) => <option key={x}>{x}</option>)}</Select>}</FormField>
            <FormField label="Vencimiento" required description="Por ejemplo: Día 5 de cada mes." error={errors["dueDay"]}>{(c) => <Input {...c} value={f.dueDay} onChange={(e) => setF({ ...f, dueDay: e.target.value })} />}</FormField>
            <FormField label="Estado">{(c) => <Select {...c} value={f.active ? "on" : "off"} onChange={(e) => setF({ ...f, active: e.target.value === "on" })}><option value="on">Activo</option><option value="off">Inactivo</option></Select>}</FormField>
            <FormField label="Política de mora" description="Descripción en texto; el servidor calcula la mora." className="sm:col-span-2">{(c) => <Input {...c} value={f.lateFee} onChange={(e) => setF({ ...f, lateFee: e.target.value })} />}</FormField>
          </div>
        </Card>
        <Card title="Monto por grado" className="mt-4" actions={<Button variant="secondary" size="sm" onClick={() => setAll(typed[grades[0]?.name ?? ""] ?? "")}>Copiar el primer monto a todos</Button>}>
          {errors["amounts"] ? <div className="mb-3"><Alert variant="destructive">{errors["amounts"]}</Alert></div> : null}
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {grades.map((g) => (
              <FormField key={g.id} label={g.name} error={errors[g.id]}>{(c) => <Input {...c} inputMode="decimal" placeholder="0.00" className="text-right tabular-nums" value={typed[g.name] ?? ""} onChange={(e) => setTyped({ ...typed, [g.name]: e.target.value })} />}</FormField>
            ))}
          </div>
        </Card>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => void navigate({ to: "/configuracion/tarifas" })}>Cancelar</Button>
          <Button type="submit">Guardar</Button>
        </div>
      </form>
    </>
  );
}

const TYPES: Scholarship["type"][] = ["Porcentaje", "Monto fijo"];

export function ScholarshipList() {
  const { status, retry } = useFakeLoad("scholarships");
  const items = useStore((s) => s.scholarships);
  const navigate = useNavigate();
  const [type, setType] = useState("");
  const rows = items.filter((b) => type === "" || b.type === type);
  const goNew = () => void navigate({ to: "/configuracion/becas/nueva" });
  return (
    <>
      <PageHeader title="Becas y descuentos" subtitle="Beneficios que se pueden asignar a los estudiantes." actions={<Button onClick={goNew}><Plus /> Nueva beca</Button>} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Becas y descuentos" rows={rows} rowKey={(b) => b.id} searchPlaceholder="Buscar por nombre" searchText={(b) => b.name}
          filtersActive={type !== ""} onClearFilters={() => setType("")}
          filters={<Select aria-label="Tipo" value={type} onChange={(e) => setType(e.target.value)} className="w-44"><option value="">Todos los tipos</option>{TYPES.map((x) => <option key={x}>{x}</option>)}</Select>}
          emptyTitle="Aún no hay becas" emptyDescription="Cree la primera beca o descuento." emptyAction={{ label: "Nueva beca", onAction: goNew }}
          columns={[
            { header: "Nombre", render: (b) => <span className="font-medium">{b.name}</span> },
            { header: "Tipo", render: (b) => b.type },
            { header: "Valor", align: "right", render: (b) => <Amount value={b.value} strong /> },
            { header: "Aplica a", render: (b) => b.concepts.join(", ") },
            { header: "Vigencia", render: (b) => <span className="tabular-nums">{b.validFrom} a {b.validTo}</span> },
            { header: "Estado", render: (b) => <ActiveBadge on={b.active} /> },
            { header: "Acciones", render: (b) => <Link to="/configuracion/becas/$id/editar" params={{ id: b.id }}><Button variant="subtle" size="sm"><Pencil /> Editar</Button></Link> },
          ]}
        />
      </LoadGate>
    </>
  );
}

export function ScholarshipForm({ mode }: { mode: "new" | "edit" }) {
  const { id } = useParams({ strict: false }) as { id?: string };
  const existing = useStore((s) => s.scholarships.find((x) => x.id === id));
  const fees = useStore((s) => s.fees);
  const navigate = useNavigate();
  const [f, setF] = useState<Scholarship>(existing ?? { id: "", name: "", type: "Porcentaje", value: "", concepts: [], validFrom: "", validTo: "", active: true });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const pct = f.type === "Porcentaje";
  const toggle = (n: string) => setF({ ...f, concepts: f.concepts.includes(n) ? f.concepts.filter((x) => x !== n) : [...f.concepts, n] });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.name.trim()) err["name"] = "Escriba el nombre de la beca.";
    if (pct ? !/^\s*\d{1,3}(\.\d{1,2})?\s*%?\s*$/.test(f.value) : !AMOUNT.test(f.value)) err["value"] = pct ? "Escriba un porcentaje como 25." : "Escriba un monto como 500.00.";
    if (f.concepts.length === 0) err["concepts"] = "Seleccione al menos un concepto.";
    if (!DATE.test(f.validFrom)) err["from"] = "Use el formato dd/mm/aaaa.";
    if (!DATE.test(f.validTo)) err["to"] = "Use el formato dd/mm/aaaa.";
    setErrors(err);
    if (Object.keys(err).length) return;
    const value = pct ? `${f.value.replace(/[%\s]/g, "")} %` : displayTyped(f.value);
    actions.saveScholarship({ ...f, value, id: f.id || nextId("b") });
    notify(mode === "new" ? "Beca creada." : "Cambios guardados.");
    void navigate({ to: "/configuracion/becas" });
  };
  if (mode === "edit" && !existing) return <Alert variant="warning">No encontramos la beca solicitada.</Alert>;
  return (
    <>
      <PageHeader title={mode === "new" ? "Nueva beca o descuento" : `Editar ${existing?.name ?? ""}`} crumbs={[{ label: "Becas y descuentos", to: "/configuracion/becas" }, { label: mode === "new" ? "Nueva" : "Editar" }]} />
      <form onSubmit={submit} noValidate>
        <Card>
          <div className="grid gap-5 sm:grid-cols-2">
            <FormField label="Nombre" required error={errors["name"]} className="sm:col-span-2">{(c) => <Input {...c} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />}</FormField>
            <FormField label="Tipo">{(c) => <Select {...c} value={f.type} onChange={(e) => setF({ ...f, type: e.target.value as Scholarship["type"], value: "" })}>{TYPES.map((x) => <option key={x}>{x}</option>)}</Select>}</FormField>
            <FormField label={pct ? "Porcentaje" : "Monto (L)"} required error={errors["value"]}>{(c) => <Input {...c} inputMode="decimal" className="text-right tabular-nums" value={f.value} onChange={(e) => setF({ ...f, value: e.target.value })} />}</FormField>
            <FormField label="Vigente desde" required description="dd/mm/aaaa" error={errors["from"]}>{(c) => <Input {...c} className="tabular-nums" value={f.validFrom} onChange={(e) => setF({ ...f, validFrom: e.target.value })} />}</FormField>
            <FormField label="Vigente hasta" required description="dd/mm/aaaa" error={errors["to"]}>{(c) => <Input {...c} className="tabular-nums" value={f.validTo} onChange={(e) => setF({ ...f, validTo: e.target.value })} />}</FormField>
            <FormField label="Estado">{(c) => <Select {...c} value={f.active ? "on" : "off"} onChange={(e) => setF({ ...f, active: e.target.value === "on" })}><option value="on">Activo</option><option value="off">Inactivo</option></Select>}</FormField>
            <fieldset className="flex flex-col gap-2 sm:col-span-2">
              <legend className="mb-1 text-sm font-medium text-text-primary">Aplica a estos conceptos <span className="font-normal text-text-secondary">Obligatorio</span></legend>
              <div className="grid gap-2 sm:grid-cols-3">
                {fees.map((x) => <label key={x.id} className="flex items-center gap-2 rounded-md border border-border-subtle p-2 text-sm"><input type="checkbox" checked={f.concepts.includes(x.name)} onChange={() => toggle(x.name)} /> {x.name}</label>)}
              </div>
              {errors["concepts"] ? <p role="alert" className="text-sm text-status-overdue-fg">{errors["concepts"]}</p> : null}
            </fieldset>
          </div>
        </Card>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => void navigate({ to: "/configuracion/becas" })}>Cancelar</Button>
          <Button type="submit">Guardar</Button>
        </div>
      </form>
    </>
  );
}
