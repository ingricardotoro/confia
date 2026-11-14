import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { Alert, Button, EmptyState, FormField, Input } from "@confia/ui";
import { Pencil, Plus } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Amount, Card, DataTable, DetailList, LoadGate, PageHeader, Select, StatusFor } from "../lib/ui";
import type { Guardian } from "../mock/data";
import { actions, nextId, notify, useFakeLoad, useStore } from "../mock/store";

const RELATIONS = ["Madre", "Padre", "Tutor legal", "Tutora legal", "Abuelo o abuela", "Otro"];

export function GuardianList() {
  const { status, retry } = useFakeLoad("guardians");
  const guardians = useStore((s) => s.guardians);
  const navigate = useNavigate();
  const [rel, setRel] = useState("");
  const rows = guardians.filter((g) => rel === "" || g.relation === rel);
  const goNew = () => void navigate({ to: "/encargados/nuevo" });
  return (
    <>
      <PageHeader title="Encargados" subtitle="Madres, padres y tutores responsables del pago." actions={<Button onClick={goNew}><Plus /> Nuevo encargado</Button>} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Encargados" rows={rows} rowKey={(g) => g.id} searchPlaceholder="Buscar por nombre, teléfono o correo"
          searchText={(g) => `${g.name} ${g.phone} ${g.email}`} filtersActive={rel !== ""} onClearFilters={() => setRel("")}
          filters={<Select aria-label="Parentesco" value={rel} onChange={(e) => setRel(e.target.value)} className="w-48"><option value="">Todos los parentescos</option>{RELATIONS.map((r) => <option key={r}>{r}</option>)}</Select>}
          emptyTitle="Aún no hay encargados" emptyDescription="Registre al primer encargado para vincularlo con sus estudiantes." emptyAction={{ label: "Nuevo encargado", onAction: goNew }}
          columns={[
            { header: "Nombre", render: (g) => <Link to="/encargados/$id" params={{ id: g.id }} className="font-medium text-text-link hover:underline">{g.name}</Link> },
            { header: "Parentesco", render: (g) => g.relation },
            { header: "Teléfono", render: (g) => <span className="tabular-nums">{g.phone}</span> },
            { header: "Correo", render: (g) => g.email },
            { header: "Estudiantes", align: "right", render: (g) => <span className="tabular-nums">{g.studentIds.length}</span> },
          ]}
        />
      </LoadGate>
    </>
  );
}

export function GuardianDetail() {
  const { id } = useParams({ strict: false }) as { id: string };
  const { status, retry } = useFakeLoad(`guardian-${id}`);
  const guardian = useStore((s) => s.guardians.find((g) => g.id === id));
  const students = useStore((s) => s.students);
  const linked = students.filter((s) => guardian?.studentIds.includes(s.id));
  const navigate = useNavigate();
  return (
    <LoadGate status={status} retry={retry}>
      {!guardian ? (
        <Alert variant="warning" title="Encargado no encontrado">No existe un encargado con ese identificador.</Alert>
      ) : (
        <>
          <PageHeader title={guardian.name} crumbs={[{ label: "Encargados", to: "/encargados" }, { label: guardian.name }]}
            actions={<Link to="/encargados/$id/editar" params={{ id }}><Button variant="secondary"><Pencil /> Editar</Button></Link>} />
          <Card title="Datos del encargado">
            <DetailList items={[["Nombre", guardian.name], ["Parentesco", guardian.relation], ["Teléfono", <span className="tabular-nums">{guardian.phone}</span>], ["Correo electrónico", guardian.email], ["Identidad", <span className="tabular-nums">{guardian.idNumber}</span>], ["Dirección", guardian.address]]} />
          </Card>
          <Card title="Estudiantes vinculados" className="mt-4">
            {linked.length === 0 ? (
              <EmptyState title="Sin estudiantes vinculados" description="Vincule a este encargado con un estudiante desde la edición." action={{ label: "Editar encargado", onAction: () => void navigate({ to: "/encargados/$id/editar", params: { id } }) }} />
            ) : (
              <ul className="divide-y divide-border-subtle">
                {linked.map((s) => (
                  <li key={s.id} className="flex items-center justify-between gap-3 py-3 text-sm">
                    <div><Link to="/estudiantes/$id" params={{ id: s.id }} className="font-medium text-text-link hover:underline">{s.name}</Link><p className="text-text-secondary">{s.grade} {s.section}</p></div>
                    <div className="flex items-center gap-3"><StatusFor status={s.balanceStatus} /><Amount value={s.balance} /></div>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </>
      )}
    </LoadGate>
  );
}

export function GuardianForm({ mode }: { mode: "new" | "edit" }) {
  const { id } = useParams({ strict: false }) as { id?: string };
  const existing = useStore((s) => s.guardians.find((g) => g.id === id));
  const students = useStore((s) => s.students);
  const navigate = useNavigate();
  const [f, setF] = useState<Guardian>(existing ?? { id: "", name: "", relation: "Madre", phone: "", email: "", idNumber: "", address: "", studentIds: [] });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const toggle = (sid: string) => setF({ ...f, studentIds: f.studentIds.includes(sid) ? f.studentIds.filter((x) => x !== sid) : [...f.studentIds, sid] });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.name.trim()) err["name"] = "Escriba el nombre completo.";
    if (!f.phone.trim()) err["phone"] = "Escriba un teléfono de contacto.";
    if (f.email && !/^\S+@\S+\.\S+$/.test(f.email)) err["email"] = "Escriba un correo electrónico válido.";
    setErrors(err);
    if (Object.keys(err).length) return;
    const saved = { ...f, id: f.id || nextId("g"), idNumber: f.idNumber || "0801-••••-•••••" };
    actions.saveGuardian(saved);
    notify(mode === "new" ? "Encargado registrado." : "Cambios guardados.");
    void navigate({ to: "/encargados/$id", params: { id: saved.id } });
  };
  if (mode === "edit" && !existing) return <Alert variant="warning">No encontramos al encargado solicitado.</Alert>;
  return (
    <>
      <PageHeader title={mode === "new" ? "Nuevo encargado" : `Editar a ${existing?.name ?? ""}`} crumbs={[{ label: "Encargados", to: "/encargados" }, { label: mode === "new" ? "Nuevo" : "Editar" }]} />
      <form onSubmit={submit} noValidate>
        <Card>
          <div className="grid gap-5 sm:grid-cols-2">
            <FormField label="Nombre completo" required error={errors["name"]}>{(c) => <Input {...c} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />}</FormField>
            <FormField label="Parentesco">{(c) => <Select {...c} value={f.relation} onChange={(e) => setF({ ...f, relation: e.target.value })}>{RELATIONS.map((r) => <option key={r}>{r}</option>)}</Select>}</FormField>
            <FormField label="Teléfono" required error={errors["phone"]}>{(c) => <Input {...c} type="tel" value={f.phone} onChange={(e) => setF({ ...f, phone: e.target.value })} />}</FormField>
            <FormField label="Correo electrónico" error={errors["email"]}>{(c) => <Input {...c} type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} />}</FormField>
            <FormField label="Dirección" className="sm:col-span-2">{(c) => <Input {...c} value={f.address} onChange={(e) => setF({ ...f, address: e.target.value })} />}</FormField>
            <fieldset className="flex flex-col gap-2 sm:col-span-2">
              <legend className="mb-1 text-sm font-medium text-text-primary">Estudiantes vinculados</legend>
              <div className="grid gap-2 sm:grid-cols-2">
                {students.map((s) => (
                  <label key={s.id} className="flex items-center gap-2 rounded-md border border-border-subtle p-2 text-sm">
                    <input type="checkbox" checked={f.studentIds.includes(s.id)} onChange={() => toggle(s.id)} /> {s.name}
                  </label>
                ))}
              </div>
            </fieldset>
          </div>
        </Card>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => void navigate({ to: "/encargados" })}>Cancelar</Button>
          <Button type="submit">Guardar</Button>
        </div>
      </form>
    </>
  );
}
