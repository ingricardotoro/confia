import { Link, useNavigate, useParams } from "@tanstack/react-router";
import { Alert, Badge, Button, FormField, Input } from "@confia/ui";
import { Pencil, Plus, ShieldCheck, ShieldOff } from "lucide-react";
import { useState, type FormEvent } from "react";
import { Card, DataTable, DetailList, LoadGate, Modal, PageHeader, Select } from "../lib/ui";
import { ROLES, type Role, type StaffUser } from "../mock/data";
import { actions, nextId, notify, useFakeLoad, useStore } from "../mock/store";

export const MfaBadge = ({ on }: { on: boolean }) => (
  <Badge size="sm" variant={on ? "primary" : "outline"} icon={on ? <ShieldCheck /> : <ShieldOff />}>{on ? "MFA activa" : "Sin MFA"}</Badge>
);
const ActiveBadge = ({ active }: { active: boolean }) => <Badge size="sm" variant={active ? "primary" : "neutral"}>{active ? "Activo" : "Inactivo"}</Badge>;

export function UserList() {
  const { status, retry } = useFakeLoad("users");
  const staff = useStore((s) => s.staff);
  const navigate = useNavigate();
  const [role, setRole] = useState("");
  const [state, setState] = useState("");
  const rows = staff.filter((u) => (role === "" || u.role === role) && (state === "" || (state === "on") === u.active));
  const goNew = () => void navigate({ to: "/usuarios/nuevo" });
  return (
    <>
      <PageHeader title="Usuarios del personal" subtitle="Personas con acceso al panel y su rol." actions={<Button onClick={goNew}><Plus /> Nuevo usuario</Button>} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Usuarios del personal" rows={rows} rowKey={(u) => u.id} searchPlaceholder="Buscar por nombre o correo"
          searchText={(u) => `${u.name} ${u.email}`} filtersActive={role !== "" || state !== ""} onClearFilters={() => { setRole(""); setState(""); }}
          filters={
            <>
              <Select aria-label="Rol" value={role} onChange={(e) => setRole(e.target.value)} className="w-56"><option value="">Todos los roles</option>{ROLES.map((r) => <option key={r}>{r}</option>)}</Select>
              <Select aria-label="Estado" value={state} onChange={(e) => setState(e.target.value)} className="w-40"><option value="">Todos</option><option value="on">Activos</option><option value="off">Inactivos</option></Select>
            </>
          }
          emptyTitle="Aún no hay usuarios" emptyDescription="Cree el primer usuario del personal." emptyAction={{ label: "Nuevo usuario", onAction: goNew }}
          columns={[
            { header: "Nombre", render: (u) => <Link to="/usuarios/$id" params={{ id: u.id }} className="font-medium text-text-link hover:underline">{u.name}</Link> },
            { header: "Correo", render: (u) => u.email },
            { header: "Rol", render: (u) => u.role },
            { header: "MFA", render: (u) => <MfaBadge on={u.mfa} /> },
            { header: "Último acceso", render: (u) => <span className="tabular-nums">{u.lastAccess}</span> },
            { header: "Estado", render: (u) => <ActiveBadge active={u.active} /> },
          ]}
        />
      </LoadGate>
    </>
  );
}

export function UserDetail() {
  const { id } = useParams({ strict: false }) as { id: string };
  const { status, retry } = useFakeLoad(`user-${id}`);
  const user = useStore((s) => s.staff.find((u) => u.id === id));
  const [confirm, setConfirm] = useState(false);
  return (
    <LoadGate status={status} retry={retry}>
      {!user ? (
        <Alert variant="warning" title="Usuario no encontrado">No existe un usuario con ese identificador.</Alert>
      ) : (
        <>
          <PageHeader title={user.name} crumbs={[{ label: "Usuarios del personal", to: "/usuarios" }, { label: user.name }]}
            actions={
              <>
                <Link to="/usuarios/$id/editar" params={{ id }}><Button variant="secondary"><Pencil /> Editar</Button></Link>
                <Button variant={user.active ? "destructive" : "primary"} onClick={() => setConfirm(true)}>{user.active ? "Desactivar" : "Activar"}</Button>
              </>
            } />
          {!user.mfa ? <Alert variant="warning" title="Verificación en dos pasos pendiente">Este usuario aún no ha activado la verificación en dos pasos (MFA).</Alert> : null}
          <Card title="Datos del usuario" className="mt-4">
            <DetailList items={[["Nombre", user.name], ["Correo electrónico", user.email], ["Rol", user.role], ["MFA", <MfaBadge on={user.mfa} />], ["Estado", <ActiveBadge active={user.active} />], ["Último acceso", <span className="tabular-nums">{user.lastAccess}</span>]]} />
          </Card>
          {confirm ? (
            <Modal title={user.active ? "Desactivar usuario" : "Activar usuario"} onClose={() => setConfirm(false)}
              footer={<><Button variant="secondary" onClick={() => setConfirm(false)}>Cancelar</Button>
                <Button variant={user.active ? "destructive" : "primary"} onClick={() => { actions.saveStaff({ ...user, active: !user.active }); setConfirm(false); notify(user.active ? "Usuario desactivado." : "Usuario activado."); }}>{user.active ? "Desactivar" : "Activar"}</Button></>}>
              {user.active ? `${user.name} ya no podrá ingresar al panel. Puede volver a activarlo cuando lo necesite.` : `${user.name} podrá ingresar de nuevo al panel con su rol actual.`}
            </Modal>
          ) : null}
        </>
      )}
    </LoadGate>
  );
}

export function UserForm({ mode }: { mode: "new" | "edit" }) {
  const { id } = useParams({ strict: false }) as { id?: string };
  const existing = useStore((s) => s.staff.find((u) => u.id === id));
  const navigate = useNavigate();
  const [f, setF] = useState<StaffUser>(existing ?? { id: "", name: "", email: "", role: "Cajero", mfa: false, active: true, lastAccess: "Nunca" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    if (!f.name.trim()) err["name"] = "Escriba el nombre completo.";
    if (!/^\S+@\S+\.\S+$/.test(f.email)) err["email"] = "Escriba un correo electrónico válido.";
    setErrors(err);
    if (Object.keys(err).length) return;
    const saved = { ...f, id: f.id || nextId("u") };
    actions.saveStaff(saved);
    notify(mode === "new" ? "Usuario creado. Recibirá una invitación por correo." : "Cambios guardados.");
    void navigate({ to: "/usuarios/$id", params: { id: saved.id } });
  };
  if (mode === "edit" && !existing) return <Alert variant="warning">No encontramos el usuario solicitado.</Alert>;
  return (
    <>
      <PageHeader title={mode === "new" ? "Nuevo usuario" : `Editar a ${existing?.name ?? ""}`} crumbs={[{ label: "Usuarios del personal", to: "/usuarios" }, { label: mode === "new" ? "Nuevo" : "Editar" }]} />
      <form onSubmit={submit} noValidate>
        <Card>
          <div className="grid gap-5 sm:grid-cols-2">
            <FormField label="Nombre completo" required error={errors["name"]}>{(c) => <Input {...c} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />}</FormField>
            <FormField label="Correo electrónico" required error={errors["email"]}>{(c) => <Input {...c} type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} />}</FormField>
            <FormField label="Rol" description="El rol define lo que la persona puede ver y hacer.">{(c) => <Select {...c} value={f.role} onChange={(e) => setF({ ...f, role: e.target.value as Role })}>{ROLES.map((r) => <option key={r}>{r}</option>)}</Select>}</FormField>
            <FormField label="Estado">{(c) => <Select {...c} value={f.active ? "on" : "off"} onChange={(e) => setF({ ...f, active: e.target.value === "on" })}><option value="on">Activo</option><option value="off">Inactivo</option></Select>}</FormField>
          </div>
        </Card>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="secondary" onClick={() => void navigate({ to: "/usuarios" })}>Cancelar</Button>
          <Button type="submit">Guardar</Button>
        </div>
      </form>
    </>
  );
}
