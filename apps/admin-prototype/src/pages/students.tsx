import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { Alert, Badge, Button, EmptyState, FormField, Input } from "@confia/ui";
import { HandCoins, Pencil, Plus } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Amount, Card, DataTable, DetailList, LoadGate, PageHeader, Select, StatusFor, Tabs } from "../lib/ui";
import { GRADES, SECTIONS, type Student } from "../mock/data";
import { actions, nextId, notify, useFakeLoad, useStore } from "../mock/store";

export function StudentList() {
  const { status, retry } = useFakeLoad("students");
  const students = useStore((s) => s.students);
  const navigate = useNavigate();
  const [grade, setGrade] = useState("");
  const [section, setSection] = useState("");
  const [bal, setBal] = useState("");
  const rows = students.filter((s) => (grade === "" || s.grade === grade) && (section === "" || s.section === section) && (bal === "" || (bal === "debt" ? s.balanceStatus !== "paid" : s.balanceStatus === "paid")));
  const goNew = () => void navigate({ to: "/estudiantes/nuevo" });
  return (
    <>
      <PageHeader title="Estudiantes" subtitle="Matrícula del año lectivo 2026." actions={<Button onClick={goNew}><Plus /> Nuevo estudiante</Button>} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Estudiantes" rows={rows} rowKey={(s) => s.id} searchPlaceholder="Buscar por nombre o código"
          searchText={(s) => `${s.name} ${s.code}`} filtersActive={grade !== "" || section !== "" || bal !== ""} onClearFilters={() => { setGrade(""); setSection(""); setBal(""); }}
          filters={
            <>
              <Select aria-label="Grado" value={grade} onChange={(e) => setGrade(e.target.value)} className="w-44"><option value="">Todos los grados</option>{GRADES.map((g) => <option key={g}>{g}</option>)}</Select>
              <Select aria-label="Sección" value={section} onChange={(e) => setSection(e.target.value)} className="w-36"><option value="">Todas las secciones</option>{SECTIONS.map((g) => <option key={g}>{g}</option>)}</Select>
              <Select aria-label="Saldo" value={bal} onChange={(e) => setBal(e.target.value)} className="w-44"><option value="">Cualquier saldo</option><option value="debt">Con saldo pendiente</option><option value="ok">Al día</option></Select>
            </>
          }
          emptyTitle="Aún no hay estudiantes" emptyDescription="Registre al primer estudiante para generar sus cargos." emptyAction={{ label: "Nuevo estudiante", onAction: goNew }}
          columns={[
            { header: "Código", render: (s) => <span className="tabular-nums">{s.code}</span> },
            { header: "Estudiante", render: (s) => <Link to="/estudiantes/$id" params={{ id: s.id }} className="font-medium text-text-link hover:underline">{s.name}</Link> },
            { header: "Grado", render: (s) => `${s.grade} ${s.section}` },
            { header: "Estado", render: (s) => <StatusFor status={s.balanceStatus} /> },
            { header: "Saldo", align: "right", render: (s) => <Amount value={s.balance} strong={s.balanceStatus === "overdue"} /> },
          ]}
        />
      </LoadGate>
    </>
  );
}

export function StudentDetail() {
  const { id } = useParams({ strict: false }) as { id: string };
  const { status, retry } = useFakeLoad(`student-${id}`);
  const student = useStore((s) => s.students.find((x) => x.id === id));
  const guardians = useStore((s) => s.guardians);
  const payments = useStore((s) => s.payments);
  const [tab, setTab] = useState("datos");
  const navigate = useNavigate();
  return (
    <LoadGate status={status} retry={retry}>
      {!student ? (
        <Alert variant="warning" title="Estudiante no encontrado">No existe un estudiante con ese identificador.</Alert>
      ) : (
        <>
          <PageHeader title={student.name} subtitle={`${student.code} · ${student.grade} ${student.section}`} crumbs={[{ label: "Estudiantes", to: "/estudiantes" }, { label: student.name }]}
            actions={
              <>
                <Link to="/estudiantes/$id/editar" params={{ id }}><Button variant="secondary"><Pencil /> Editar</Button></Link>
                <Link to="/pagos/nuevo" search={{ student: id }}><Button><HandCoins /> Registrar pago</Button></Link>
              </>
            } />
          <div className="mb-4 flex items-center gap-3 rounded-lg border border-border-subtle bg-bg-surface p-4">
            <span className="text-sm text-text-secondary">Saldo pendiente</span>
            <Amount value={student.balance} strong />
            <StatusFor status={student.balanceStatus} />
            {student.balanceStale ? <Badge size="sm" variant="outline">Saldo en recálculo en el servidor</Badge> : null}
          </div>
          <Tabs value={tab} onChange={setTab} tabs={[{ id: "datos", label: "Datos" }, { id: "encargados", label: "Encargados" }, { id: "cuenta", label: "Estado de cuenta" }, { id: "pagos", label: "Pagos" }]} />
          {tab === "datos" ? (
            <Card><DetailList items={[["Nombre", student.name], ["Código", <span className="tabular-nums">{student.code}</span>], ["Grado y sección", `${student.grade} ${student.section}`], ["Jornada", student.shift], ["Fecha de matrícula", <span className="tabular-nums">{student.enrolledOn}</span>], ["Estado", student.active ? "Activo" : "Inactivo"]]} /></Card>
          ) : null}
          {tab === "encargados" ? (
            <Card>
              {student.guardianIds.length === 0 ? (
                <EmptyState title="Sin encargados" description="Este estudiante aún no tiene encargados vinculados." action={{ label: "Agregar encargado", onAction: () => void navigate({ to: "/encargados/nuevo" }) }} />
              ) : (
                <ul className="divide-y divide-border-subtle">
                  {student.guardianIds.map((gid) => {
                    const g = guardians.find((x) => x.id === gid);
                    return g ? (
                      <li key={gid} className="flex items-center justify-between py-3 text-sm">
                        <div><Link to="/encargados/$id" params={{ id: gid }} className="font-medium text-text-link hover:underline">{g.name}</Link><p className="text-text-secondary">{g.relation} · {g.phone}</p></div>
                      </li>
                    ) : null;
                  })}
                </ul>
              )}
            </Card>
          ) : null}
          {tab === "cuenta" ? <AccountStatement studentId={id} /> : null}
          {tab === "pagos" ? (
            <Card className="p-0">
              {payments.filter((p) => p.studentId === id).length === 0 ? (
                <EmptyState title="Sin pagos" description="Este estudiante aún no tiene pagos registrados." action={{ label: "Registrar pago", onAction: () => void navigate({ to: "/pagos/nuevo", search: { student: id } }) }} />
              ) : (
                <table className="w-full text-sm">
                  <caption className="sr-only">Pagos del estudiante</caption>
                  <thead className="bg-bg-canvas text-text-secondary"><tr><th scope="col" className="px-4 py-3 text-left font-medium">Recibo</th><th scope="col" className="px-4 py-3 text-left font-medium">Fecha</th><th scope="col" className="px-4 py-3 text-left font-medium">Método</th><th scope="col" className="px-4 py-3 text-left font-medium">Estado</th><th scope="col" className="px-4 py-3 text-right font-medium">Monto</th></tr></thead>
                  <tbody className="divide-y divide-border-subtle">
                    {payments.filter((p) => p.studentId === id).map((p) => (
                      <tr key={p.id}><td className="px-4 py-3"><Link to="/pagos/$id" params={{ id: p.id }} className="font-medium text-text-link hover:underline">{p.number}</Link></td><td className="px-4 py-3 tabular-nums">{p.date}</td><td className="px-4 py-3">{p.method}</td><td className="px-4 py-3"><StatusFor status={p.status} /></td><td className="px-4 py-3 text-right"><Amount value={p.amount} /></td></tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Card>
          ) : null}
        </>
      )}
    </LoadGate>
  );
}

export function AccountStatement({ studentId }: { studentId: string }) {
  const student = useStore((s) => s.students.find((x) => x.id === studentId));
  if (!student) return null;
  return (
    <Card className="p-0">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <caption className="sr-only">Estado de cuenta de {student.name}</caption>
          <thead className="bg-bg-canvas text-text-secondary"><tr><th scope="col" className="px-4 py-3 text-left font-medium">Concepto</th><th scope="col" className="px-4 py-3 text-left font-medium">Vence</th><th scope="col" className="px-4 py-3 text-left font-medium">Estado</th><th scope="col" className="px-4 py-3 text-right font-medium">Monto</th></tr></thead>
          <tbody className="divide-y divide-border-subtle">
            {student.charges.map((c) => (
              <tr key={c.id}><td className="px-4 py-3">{c.concept}</td><td className="px-4 py-3 tabular-nums">{c.due}</td><td className="px-4 py-3"><StatusFor status={c.status} /></td><td className="px-4 py-3 text-right"><Amount value={c.amount} /></td></tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex justify-end gap-3 border-t border-border-subtle px-4 py-3 text-sm"><span className="text-text-secondary">Saldo pendiente</span><Amount value={student.balance} strong /></div>
    </Card>
  );
}

export function StudentForm({ mode }: { mode: "new" | "edit" }) {
  const { id } = useParams({ strict: false }) as { id?: string };
  const existing = useStore((s) => s.students.find((x) => x.id === id));
  const guardians = useStore((s) => s.guardians);
  const navigate = useNavigate();
  const [f, setF] = useState<Student>(existing ?? { id: "", code: "", name: "", grade: GRADES[0]!, section: "A", active: true, balance: "L 0.00", balanceStatus: "paid", guardianIds: [], charges: [], enrolledOn: "07/11/2026", shift: "Matutina" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const toggle = (gid: string) => setF({ ...f, guardianIds: f.guardianIds.includes(gid) ? f.guardianIds.filter((x) => x !== gid) : [...f.guardianIds, gid] });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.name.trim()) err["name"] = "Escriba el nombre completo del estudiante.";
    if (f.guardianIds.length === 0) err["guardians"] = "Vincule al menos un encargado.";
    setErrors(err);
    if (Object.keys(err).length) return;
    const nid = f.id || nextId("s");
    const saved = { ...f, id: nid, code: f.code || `E-2026-${nid.replace("s", "").padStart(4, "0")}` };
    actions.saveStudent(saved);
    notify(mode === "new" ? "Estudiante registrado." : "Cambios guardados.");
    void navigate({ to: "/estudiantes/$id", params: { id: nid } });
  };
  if (mode === "edit" && !existing) return <Alert variant="warning">No encontramos al estudiante solicitado.</Alert>;
  return (
    <>
      <PageHeader title={mode === "new" ? "Nuevo estudiante" : `Editar a ${existing?.name ?? ""}`} crumbs={[{ label: "Estudiantes", to: "/estudiantes" }, { label: mode === "new" ? "Nuevo" : "Editar" }]} />
      <form onSubmit={submit} noValidate>
        <Card>
          <div className="grid gap-5 sm:grid-cols-2">
            <FormField label="Nombre completo" required error={errors["name"]} className="sm:col-span-2">{(c) => <Input {...c} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />}</FormField>
            <FormField label="Grado">{(c) => <Select {...c} value={f.grade} onChange={(e) => setF({ ...f, grade: e.target.value })}>{GRADES.map((g) => <option key={g}>{g}</option>)}</Select>}</FormField>
            <FormField label="Sección">{(c) => <Select {...c} value={f.section} onChange={(e) => setF({ ...f, section: e.target.value })}>{SECTIONS.map((g) => <option key={g}>{g}</option>)}</Select>}</FormField>
            <FormField label="Jornada">{(c) => <Select {...c} value={f.shift} onChange={(e) => setF({ ...f, shift: e.target.value })}><option>Matutina</option><option>Vespertina</option></Select>}</FormField>
            <FormField label="Estado">{(c) => <Select {...c} value={f.active ? "on" : "off"} onChange={(e) => setF({ ...f, active: e.target.value === "on" })}><option value="on">Activo</option><option value="off">Inactivo</option></Select>}</FormField>
            <fieldset className="flex flex-col gap-2 sm:col-span-2">
              <legend className="mb-1 text-sm font-medium text-text-primary">Encargados <span className="font-normal text-text-secondary">Obligatorio</span></legend>
              <div className="grid gap-2 sm:grid-cols-2">
                {guardians.map((g) => (
                  <label key={g.id} className="flex items-center gap-2 rounded-md border border-border-subtle p-2 text-sm">
                    <input type="checkbox" checked={f.guardianIds.includes(g.id)} onChange={() => toggle(g.id)} /> {g.name} <span className="text-text-secondary">({g.relation})</span>
                  </label>
                ))}
              </div>
              {errors["guardians"] ? <p role="alert" className="text-sm text-status-overdue-fg">{errors["guardians"]}</p> : null}
            </fieldset>
          </div>
        </Card>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => void navigate({ to: "/estudiantes" })}>Cancelar</Button>
          <Button type="submit">Guardar</Button>
        </div>
      </form>
    </>
  );
}
