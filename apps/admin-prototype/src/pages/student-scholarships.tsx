import { Link } from "@tanstack/react-router";
import { Badge, Button, EmptyState } from "@confia/ui";
import { useState } from "react";
import { Amount, Card, Select } from "../lib/ui";
import { actions, notify, useStore } from "../mock/store";

export function StudentScholarships({ studentId }: { studentId: string }) {
  const assigned = useStore((s) => s.studentScholarships).filter((a) => a.studentId === studentId);
  const all = useStore((s) => s.scholarships);
  const [pick, setPick] = useState("");
  const rows = assigned.flatMap((a) => {
    const b = all.find((x) => x.id === a.scholarshipId);
    return b ? [{ a, b }] : [];
  });
  const available = all.filter((b) => b.active && !assigned.some((a) => a.scholarshipId === b.id));
  return (
    <>
      <Card title="Becas y descuentos asignados" className="mb-4">
        {rows.length === 0 ? (
          <EmptyState title="Sin becas asignadas" description="Este estudiante no tiene becas ni descuentos. Puede asignar uno desde el formulario de abajo." action={{ label: "Ver becas disponibles", onAction: () => document.getElementById("beca-select")?.focus() }} />
        ) : (
          <ul className="divide-y divide-border-subtle">
            {rows.map(({ a, b }) => (
              <li key={b.id} className="flex flex-wrap items-center justify-between gap-3 py-3 text-sm">
                <div>
                  <p className="font-medium text-text-primary">{b.name} <Badge size="sm" variant="outline">{b.type}</Badge></p>
                  <p className="text-text-secondary">Aplica a {b.concepts.join(", ")} · Asignada el <span className="tabular-nums">{a.since}</span> · Vigente hasta <span className="tabular-nums">{b.validTo}</span></p>
                </div>
                <div className="flex items-center gap-3">
                  <Amount value={b.value} strong />
                  <Button variant="subtle" size="sm" onClick={() => { actions.removeScholarship(studentId, b.id); notify("Beca retirada."); }}>Retirar</Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Asignar una beca">
        <div className="flex flex-wrap items-end gap-3">
          <div className="w-full max-w-md">
            <label htmlFor="beca-select" className="mb-1.5 block text-sm font-medium text-text-primary">Beca o descuento</label>
            <Select id="beca-select" value={pick} onChange={(e) => setPick(e.target.value)}>
              <option value="">{available.length === 0 ? "No hay becas disponibles" : "Seleccione una beca"}</option>
              {available.map((b) => <option key={b.id} value={b.id}>{b.name} ({b.value})</option>)}
            </Select>
          </div>
          <Button disabled={pick === ""} onClick={() => { actions.assignScholarship({ studentId, scholarshipId: pick, since: "07/11/2026" }); setPick(""); notify("Beca asignada."); }}>Asignar</Button>
          <Link to="/configuracion/becas" className="text-sm text-text-link hover:underline">Administrar becas</Link>
        </div>
        <p className="mt-3 text-xs text-text-tertiary">El efecto de la beca sobre los cargos lo calcula el servidor; el prototipo no recalcula saldos.</p>
      </Card>
    </>
  );
}
