import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { Alert, Button, FormField, Input } from "@confia/ui";
import { Pencil, Plus } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Card, DataTable, DetailList, LoadGate, PageHeader, Select } from "../lib/ui";
import type { Org } from "../mock/data";
import { actions, nextId, notify, useFakeLoad, useStore } from "../mock/store";

const ActiveBadge = ({ active }: { active: boolean }) => (
  <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs font-medium ${active ? "border-status-paid-border bg-status-paid-bg text-status-paid-fg" : "border-status-void-border bg-status-void-bg text-status-void-fg"}`}>
    {active ? "Activa" : "Inactiva"}
  </span>
);

export function OrgList() {
  const { status, retry } = useFakeLoad("orgs");
  const orgs = useStore((s) => s.orgs);
  const navigate = useNavigate();
  const [state, setState] = useState("");
  const [city, setCity] = useState("");
  const rows = orgs.filter((o) => (state === "" || (state === "on") === o.active) && (city === "" || o.city === city));
  const cities = [...new Set(orgs.map((o) => o.city))];
  const goNew = () => void navigate({ to: "/organizaciones/nueva" });
  return (
    <>
      <PageHeader title="Instituciones" subtitle="Colegios y escuelas registrados en CONFIA." actions={<Button onClick={goNew}><Plus /> Nueva institución</Button>} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Instituciones" rows={rows} rowKey={(o) => o.id} searchPlaceholder="Buscar por nombre o RTN"
          searchText={(o) => `${o.name} ${o.rtn} ${o.city}`}
          filtersActive={state !== "" || city !== ""} onClearFilters={() => { setState(""); setCity(""); }}
          filters={
            <>
              <Select aria-label="Estado" value={state} onChange={(e) => setState(e.target.value)} className="w-40"><option value="">Todos los estados</option><option value="on">Activas</option><option value="off">Inactivas</option></Select>
              <Select aria-label="Ciudad" value={city} onChange={(e) => setCity(e.target.value)} className="w-48"><option value="">Todas las ciudades</option>{cities.map((c) => <option key={c}>{c}</option>)}</Select>
            </>
          }
          emptyTitle="Aún no hay instituciones" emptyDescription="Registre la primera institución para comenzar." emptyAction={{ label: "Nueva institución", onAction: goNew }}
          columns={[
            { header: "Institución", render: (o) => <Link to="/organizaciones/$id" params={{ id: o.id }} className="font-medium text-text-link hover:underline">{o.name}</Link> },
            { header: "RTN", render: (o) => <span className="tabular-nums">{o.rtn}</span> },
            { header: "Ciudad", render: (o) => o.city },
            { header: "Tipo", render: (o) => o.kind },
            { header: "Estudiantes", align: "right", render: (o) => <span className="tabular-nums">{o.studentCount}</span> },
            { header: "Estado", render: (o) => <ActiveBadge active={o.active} /> },
          ]}
        />
      </LoadGate>
    </>
  );
}

export function OrgDetail() {
  const { id } = useParams({ strict: false }) as { id: string };
  const { status, retry } = useFakeLoad(`org-${id}`);
  const org = useStore((s) => s.orgs.find((o) => o.id === id));
  return (
    <LoadGate status={status} retry={retry}>
      {!org ? (
        <Alert variant="warning" title="Institución no encontrada">No existe una institución con ese identificador. <Link to="/organizaciones" className="underline">Volver al listado</Link></Alert>
      ) : (
        <>
          <PageHeader title={org.name} crumbs={[{ label: "Instituciones", to: "/organizaciones" }, { label: org.name }]}
            actions={<Link to="/organizaciones/$id/editar" params={{ id }}><Button variant="secondary"><Pencil /> Editar</Button></Link>} />
          <Card title="Datos de la institución">
            <DetailList items={[
              ["Nombre", org.name], ["RTN", <span className="tabular-nums">{org.rtn}</span>], ["Tipo", org.kind], ["Ciudad", org.city],
              ["Dirección o director", org.director], ["Teléfono", org.phone], ["Correo electrónico", org.email],
              ["Estudiantes", <span className="tabular-nums">{org.studentCount}</span>], ["Estado", <ActiveBadge active={org.active} />],
            ]} />
          </Card>
        </>
      )}
    </LoadGate>
  );
}

export function OrgForm({ mode }: { mode: "new" | "edit" }) {
  const { id } = useParams({ strict: false }) as { id?: string };
  const existing = useStore((s) => s.orgs.find((o) => o.id === id));
  const navigate = useNavigate();
  const [f, setF] = useState<Org>(existing ?? { id: "", name: "", rtn: "", kind: "Colegio privado", city: "", director: "", phone: "", email: "", active: true, studentCount: "0" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const set = (k: keyof Org, v: string | boolean) => setF({ ...f, [k]: v });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.name.trim()) err["name"] = "Escriba el nombre de la institución.";
    if (!/^\d{14}$/.test(f.rtn)) err["rtn"] = "El RTN tiene 14 dígitos, sin guiones.";
    if (!f.city.trim()) err["city"] = "Indique la ciudad.";
    setErrors(err);
    if (Object.keys(err).length) return;
    const saved = { ...f, id: f.id || nextId("o") };
    actions.saveOrg(saved);
    notify(mode === "new" ? "Institución creada." : "Cambios guardados.");
    void navigate({ to: "/organizaciones/$id", params: { id: saved.id } });
  };

  if (mode === "edit" && !existing) return <Alert variant="warning">No encontramos la institución solicitada.</Alert>;
  return (
    <>
      <PageHeader title={mode === "new" ? "Nueva institución" : `Editar ${existing?.name ?? ""}`} crumbs={[{ label: "Instituciones", to: "/organizaciones" }, { label: mode === "new" ? "Nueva" : "Editar" }]} />
      <form onSubmit={submit} noValidate>
        <Card>
          <div className="grid gap-5 sm:grid-cols-2">
            <FormField label="Nombre" required error={errors["name"]}>{(c) => <Input {...c} value={f.name} onChange={(e) => set("name", e.target.value)} />}</FormField>
            <FormField label="RTN" required description="14 dígitos, sin guiones." error={errors["rtn"]}>{(c) => <Input {...c} inputMode="numeric" value={f.rtn} onChange={(e) => set("rtn", e.target.value)} />}</FormField>
            <FormField label="Tipo de institución">{(c) => <Select {...c} value={f.kind} onChange={(e) => set("kind", e.target.value)}>{["Colegio privado", "Escuela confesional", "Instituto técnico", "Academia"].map((k) => <option key={k}>{k}</option>)}</Select>}</FormField>
            <FormField label="Ciudad" required error={errors["city"]}>{(c) => <Input {...c} value={f.city} onChange={(e) => set("city", e.target.value)} />}</FormField>
            <FormField label="Director o directora">{(c) => <Input {...c} value={f.director} onChange={(e) => set("director", e.target.value)} />}</FormField>
            <FormField label="Teléfono">{(c) => <Input {...c} value={f.phone} onChange={(e) => set("phone", e.target.value)} />}</FormField>
            <FormField label="Correo electrónico">{(c) => <Input {...c} type="email" value={f.email} onChange={(e) => set("email", e.target.value)} />}</FormField>
            <FormField label="Estado">{(c) => <Select {...c} value={f.active ? "on" : "off"} onChange={(e) => set("active", e.target.value === "on")}><option value="on">Activa</option><option value="off">Inactiva</option></Select>}</FormField>
          </div>
        </Card>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => void navigate({ to: "/organizaciones" })}>Cancelar</Button>
          <Button type="submit">Guardar</Button>
        </div>
      </form>
    </>
  );
}
