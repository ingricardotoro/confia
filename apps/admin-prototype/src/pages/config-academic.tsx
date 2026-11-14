import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { Alert, Badge, Button, FormField, Input } from "@confia/ui";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Card, DataTable, LoadGate, Modal, PageHeader, Select } from "../lib/ui";
import type { Grade, Period, PeriodStatus } from "../mock/config-data";
import { actions, nextId, notify, useFakeLoad, useStore } from "../mock/store";

const DATE = /^\d{2}\/\d{2}\/\d{4}$/;
const STATUSES: PeriodStatus[] = ["Planificado", "Activo", "Cerrado"];
const PeriodBadge = ({ s }: { s: PeriodStatus }) => <Badge size="sm" variant={s === "Activo" ? "primary" : s === "Cerrado" ? "neutral" : "outline"}>{s}</Badge>;

export function PeriodList() {
  const { status, retry } = useFakeLoad("periods");
  const periods = useStore((s) => s.periods);
  const navigate = useNavigate();
  const [st, setSt] = useState("");
  const [closing, setClosing] = useState<Period | null>(null);
  const [activating, setActivating] = useState<Period | null>(null);
  const rows = periods.filter((p) => st === "" || p.status === st);
  const goNew = () => void navigate({ to: "/configuracion/periodos/nuevo" });
  return (
    <>
      <PageHeader title="Años lectivos" subtitle="Períodos académicos de la institución. Solo uno puede estar activo." actions={<Button onClick={goNew}><Plus /> Nuevo año lectivo</Button>} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Años lectivos" rows={rows} rowKey={(p) => p.id} searchPlaceholder="Buscar por nombre" searchText={(p) => p.name}
          filtersActive={st !== ""} onClearFilters={() => setSt("")}
          filters={<Select aria-label="Estado" value={st} onChange={(e) => setSt(e.target.value)} className="w-44"><option value="">Todos los estados</option>{STATUSES.map((s) => <option key={s}>{s}</option>)}</Select>}
          emptyTitle="Aún no hay años lectivos" emptyDescription="Cree el primer año lectivo para empezar a configurar cargos." emptyAction={{ label: "Nuevo año lectivo", onAction: goNew }}
          columns={[
            { header: "Nombre", render: (p) => <span className="font-medium">{p.name}</span> },
            { header: "Inicio", render: (p) => <span className="tabular-nums">{p.start}</span> },
            { header: "Fin", render: (p) => <span className="tabular-nums">{p.end}</span> },
            { header: "Estado", render: (p) => <PeriodBadge s={p.status} /> },
            {
              header: "Acciones",
              render: (p) => (
                <div className="flex gap-1">
                  <Link to="/configuracion/periodos/$id/editar" params={{ id: p.id }}><Button variant="subtle" size="sm"><Pencil /> Editar</Button></Link>
                  {p.status === "Planificado" ? <Button variant="secondary" size="sm" onClick={() => setActivating(p)}>Activar</Button> : null}
                  {p.status === "Activo" ? <Button variant="secondary" size="sm" onClick={() => setClosing(p)}>Cerrar</Button> : null}
                </div>
              ),
            },
          ]}
        />
      </LoadGate>
      {closing ? (
        <Modal title="Cerrar año lectivo" onClose={() => setClosing(null)}
          footer={<><Button variant="secondary" onClick={() => setClosing(null)}>Cancelar</Button><Button variant="destructive" onClick={() => { actions.closePeriod(closing.id); setClosing(null); notify(`${closing.name} cerrado.`); }}>Cerrar año lectivo</Button></>}>
          Al cerrar {closing.name} ya no se podrán generar cargos ni registrar matrículas en él. Esta acción queda en la bitácora.
        </Modal>
      ) : null}
      {activating ? (
        <Modal title="Activar año lectivo" onClose={() => setActivating(null)}
          footer={<><Button variant="secondary" onClick={() => setActivating(null)}>Cancelar</Button><Button onClick={() => { actions.setActivePeriod(activating.id); setActivating(null); notify(`${activating.name} es ahora el año activo.`); }}>Activar</Button></>}>
          {activating.name} pasará a ser el año activo. El año que está activo hoy se cerrará.
        </Modal>
      ) : null}
    </>
  );
}

export function PeriodForm({ mode }: { mode: "new" | "edit" }) {
  const { id } = useParams({ strict: false }) as { id?: string };
  const existing = useStore((s) => s.periods.find((p) => p.id === id));
  const navigate = useNavigate();
  const [f, setF] = useState<Period>(existing ?? { id: "", name: "", start: "", end: "", status: "Planificado" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.name.trim()) err["name"] = "Escriba el nombre del año lectivo.";
    if (!DATE.test(f.start)) err["start"] = "Use el formato dd/mm/aaaa.";
    if (!DATE.test(f.end)) err["end"] = "Use el formato dd/mm/aaaa.";
    setErrors(err);
    if (Object.keys(err).length) return;
    actions.savePeriod({ ...f, id: f.id || nextId("y") });
    notify(mode === "new" ? "Año lectivo creado." : "Cambios guardados.");
    void navigate({ to: "/configuracion/periodos" });
  };
  if (mode === "edit" && !existing) return <Alert variant="warning">No encontramos el año lectivo solicitado.</Alert>;
  return (
    <>
      <PageHeader title={mode === "new" ? "Nuevo año lectivo" : `Editar ${existing?.name ?? ""}`} crumbs={[{ label: "Años lectivos", to: "/configuracion/periodos" }, { label: mode === "new" ? "Nuevo" : "Editar" }]} />
      <form onSubmit={submit} noValidate>
        <Card>
          <div className="grid gap-5 sm:grid-cols-2">
            <FormField label="Nombre" required error={errors["name"]} className="sm:col-span-2">{(c) => <Input {...c} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />}</FormField>
            <FormField label="Fecha de inicio" required description="dd/mm/aaaa" error={errors["start"]}>{(c) => <Input {...c} className="tabular-nums" value={f.start} onChange={(e) => setF({ ...f, start: e.target.value })} />}</FormField>
            <FormField label="Fecha de fin" required description="dd/mm/aaaa" error={errors["end"]}>{(c) => <Input {...c} className="tabular-nums" value={f.end} onChange={(e) => setF({ ...f, end: e.target.value })} />}</FormField>
            <FormField label="Estado" description="Para activar o cerrar un año use las acciones del listado.">{(c) => <Select {...c} value={f.status} disabled={mode === "edit"} onChange={(e) => setF({ ...f, status: e.target.value as PeriodStatus })}>{STATUSES.map((s) => <option key={s}>{s}</option>)}</Select>}</FormField>
          </div>
        </Card>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => void navigate({ to: "/configuracion/periodos" })}>Cancelar</Button>
          <Button type="submit">Guardar</Button>
        </div>
      </form>
    </>
  );
}

export function GradeList() {
  const { status, retry } = useFakeLoad("grades");
  const grades = useStore((s) => s.grades);
  const navigate = useNavigate();
  const goNew = () => void navigate({ to: "/configuracion/grados/nuevo" });
  return (
    <>
      <PageHeader title="Grados y secciones" subtitle="Estructura académica con la capacidad y la matrícula de cada sección." actions={<Button onClick={goNew}><Plus /> Nuevo grado</Button>} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Grados y secciones" rows={grades} rowKey={(g) => g.id} searchPlaceholder="Buscar por grado" searchText={(g) => g.name}
          emptyTitle="Aún no hay grados" emptyDescription="Cree el primer grado y sus secciones." emptyAction={{ label: "Nuevo grado", onAction: goNew }}
          pageSize={10}
          columns={[
            { header: "Grado", render: (g) => <span className="font-medium">{g.name}</span> },
            {
              header: "Secciones (matrícula / capacidad)",
              render: (g) => (
                <ul className="flex flex-wrap gap-2">
                  {g.sections.map((s) => <li key={s.id}><Badge size="sm" variant="outline">Sección {s.name}: <span className="tabular-nums">{s.enrolled} / {s.capacity}</span></Badge></li>)}
                </ul>
              ),
            },
            { header: "Acciones", render: (g) => <Link to="/configuracion/grados/$id/editar" params={{ id: g.id }}><Button variant="subtle" size="sm"><Pencil /> Editar</Button></Link> },
          ]}
        />
      </LoadGate>
    </>
  );
}

export function GradeForm({ mode }: { mode: "new" | "edit" }) {
  const { id } = useParams({ strict: false }) as { id?: string };
  const existing = useStore((s) => s.grades.find((g) => g.id === id));
  const navigate = useNavigate();
  const [f, setF] = useState<Grade>(existing ?? { id: "", name: "", sections: [{ id: nextId("sec"), name: "A", capacity: "30", enrolled: "0" }] });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const setSec = (i: number, patch: Partial<Grade["sections"][number]>) => setF({ ...f, sections: f.sections.map((s, k) => (k === i ? { ...s, ...patch } : s)) });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.name.trim()) err["name"] = "Escriba el nombre del grado.";
    if (f.sections.length === 0) err["sections"] = "Agregue al menos una sección.";
    f.sections.forEach((s, i) => {
      if (!s.name.trim()) err[`n${i}`] = "Escriba el nombre.";
      if (!/^\d{1,3}$/.test(s.capacity)) err[`c${i}`] = "Solo dígitos.";
      if (!/^\d{1,3}$/.test(s.enrolled)) err[`e${i}`] = "Solo dígitos.";
    });
    setErrors(err);
    if (Object.keys(err).length) return;
    actions.saveGrade({ ...f, id: f.id || nextId("gr") });
    notify(mode === "new" ? "Grado creado." : "Cambios guardados.");
    void navigate({ to: "/configuracion/grados" });
  };
  if (mode === "edit" && !existing) return <Alert variant="warning">No encontramos el grado solicitado.</Alert>;
  return (
    <>
      <PageHeader title={mode === "new" ? "Nuevo grado" : `Editar ${existing?.name ?? ""}`} crumbs={[{ label: "Grados y secciones", to: "/configuracion/grados" }, { label: mode === "new" ? "Nuevo" : "Editar" }]} />
      <form onSubmit={submit} noValidate>
        <Card title="Grado">
          <div className="max-w-md"><FormField label="Nombre del grado" required error={errors["name"]}>{(c) => <Input {...c} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />}</FormField></div>
        </Card>
        <Card title="Secciones" className="mt-4" actions={<Button variant="secondary" size="sm" onClick={() => setF({ ...f, sections: [...f.sections, { id: nextId("sec"), name: "", capacity: "30", enrolled: "0" }] })}><Plus /> Agregar sección</Button>}>
          {errors["sections"] ? <Alert variant="destructive">{errors["sections"]}</Alert> : null}
          <ul className="flex flex-col gap-4">
            {f.sections.map((s, i) => (
              <li key={s.id} className="grid items-start gap-4 sm:grid-cols-[1fr_1fr_1fr_auto]">
                <FormField label="Sección" required error={errors[`n${i}`]}>{(c) => <Input {...c} value={s.name} onChange={(e) => setSec(i, { name: e.target.value })} />}</FormField>
                <FormField label="Capacidad" required error={errors[`c${i}`]}>{(c) => <Input {...c} inputMode="numeric" className="tabular-nums" value={s.capacity} onChange={(e) => setSec(i, { capacity: e.target.value })} />}</FormField>
                <FormField label="Matrícula actual" required error={errors[`e${i}`]}>{(c) => <Input {...c} inputMode="numeric" className="tabular-nums" value={s.enrolled} onChange={(e) => setSec(i, { enrolled: e.target.value })} />}</FormField>
                <Button variant="subtle" aria-label={`Quitar la sección ${s.name}`} className="mt-6" onClick={() => setF({ ...f, sections: f.sections.filter((_, k) => k !== i) })}><Trash2 /></Button>
              </li>
            ))}
          </ul>
        </Card>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => void navigate({ to: "/configuracion/grados" })}>Cancelar</Button>
          <Button type="submit">Guardar</Button>
        </div>
      </form>
    </>
  );
}
