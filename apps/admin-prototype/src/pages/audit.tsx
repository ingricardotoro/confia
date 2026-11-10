import { useNavigate } from "@tanstack/react-router";
import { Alert } from "@confia/ui";
import { useState } from "react";
import { Card, DataTable, DetailList, Drawer, LoadGate, PageHeader, Pill, Select, type Tone } from "../lib/ui";
import { AUDIT_ACTIONS, AUDIT_ACTORS, AUDIT_DATES, AUDIT_ENTITIES, auditEvents, type AuditResult } from "../mock/audit-data";
import { useFakeLoad } from "../mock/store";

const RESULT_TONE: Record<AuditResult, Tone> = { Exitoso: "success", Fallido: "danger", Denegado: "danger" };

export function AuditLog() {
  const { status, retry } = useFakeLoad("audit");
  const navigate = useNavigate();
  const [actor, setActor] = useState("");
  const [action, setAction] = useState("");
  const [entity, setEntity] = useState("");
  const [date, setDate] = useState("");
  const [openId, setOpenId] = useState<string | null>(null);
  const rows = auditEvents.filter((e) => (actor === "" || e.actor === actor) && (action === "" || e.action === action) && (entity === "" || e.entity === entity) && (date === "" || e.date === date));
  const open = auditEvents.find((e) => e.id === openId);
  const clear = () => { setActor(""); setAction(""); setEntity(""); setDate(""); };
  return (
    <>
      <PageHeader title="Bitácora de auditoría" subtitle="Registro de acciones sensibles. Es de solo lectura: los eventos no se pueden editar ni borrar." />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Eventos de auditoría" rows={rows} rowKey={(e) => e.id} searchPlaceholder="Buscar por actor, entidad o IP" searchText={(e) => `${e.actor} ${e.entityRef} ${e.ip}`}
          filtersActive={actor !== "" || action !== "" || entity !== "" || date !== ""} onClearFilters={clear}
          filters={<>
            <Select aria-label="Actor" value={actor} onChange={(e) => setActor(e.target.value)} className="w-44"><option value="">Todos los actores</option>{AUDIT_ACTORS.map((x) => <option key={x}>{x}</option>)}</Select>
            <Select aria-label="Tipo de acción" value={action} onChange={(e) => setAction(e.target.value)} className="w-56"><option value="">Todas las acciones</option>{AUDIT_ACTIONS.map((x) => <option key={x}>{x}</option>)}</Select>
            <Select aria-label="Entidad" value={entity} onChange={(e) => setEntity(e.target.value)} className="w-48"><option value="">Todas las entidades</option>{AUDIT_ENTITIES.map((x) => <option key={x}>{x}</option>)}</Select>
            <Select aria-label="Fecha" value={date} onChange={(e) => setDate(e.target.value)} className="w-40"><option value="">Todas las fechas</option>{AUDIT_DATES.map((x) => <option key={x}>{x}</option>)}</Select>
          </>}
          emptyTitle="Aún no hay eventos" emptyDescription="Las acciones sensibles aparecerán aquí a medida que ocurran." emptyAction={{ label: "Ir al resumen", onAction: () => void navigate({ to: "/" }) }}
          pageSize={10}
          columns={[
            { header: "Fecha y hora", render: (e) => <button type="button" onClick={() => setOpenId(e.id)} className="font-medium tabular-nums text-text-link hover:underline">{e.date} {e.time}</button> },
            { header: "Actor", render: (e) => e.actor },
            { header: "Rol", render: (e) => e.role },
            { header: "Acción", render: (e) => e.action },
            { header: "Entidad", render: (e) => <span>{e.entity}<span className="block text-xs text-text-secondary">{e.entityRef}</span></span> },
            { header: "Resultado", render: (e) => <Pill tone={RESULT_TONE[e.result]}>{e.result}</Pill> },
            { header: "IP", render: (e) => <span className="tabular-nums">{e.ip}</span> },
          ]}
        />
      </LoadGate>
      {open ? (
        <Drawer title={open.action} onClose={() => setOpenId(null)}>
          <div className="flex flex-col gap-5">
            <Alert variant="info">Evento de solo lectura. No se puede modificar ni eliminar.</Alert>
            <DetailList items={[["Fecha y hora", <span className="tabular-nums">{open.date} {open.time}</span>], ["Resultado", <Pill tone={RESULT_TONE[open.result]}>{open.result}</Pill>], ["Actor", open.actor], ["Rol", open.role], ["Entidad", open.entity], ["Referencia", open.entityRef], ["Dirección IP", <span className="tabular-nums">{open.ip}</span>], ["Identificador del evento", open.id.toUpperCase()]]} />
            <Card title="Detalle del evento"><DetailList items={open.detail} /></Card>
          </div>
        </Drawer>
      ) : null}
    </>
  );
}
