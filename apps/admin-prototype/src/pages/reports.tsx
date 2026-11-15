import { useNavigate } from "@tanstack/react-router";
import { Button, EmptyState } from "@confia/ui";
import { Download } from "lucide-react";
import { useState } from "react";
import { Amount, Card, DataTable, DetailList, Drawer, LoadGate, MiniTable, PageHeader, Pill, Select } from "../lib/ui";
import { closings, incomeSets, portfolioBuckets, portfolioGrades, portfolioTotal } from "../mock/reports-data";
import { notify, useFakeLoad } from "../mock/store";

const ExportButton = () => <Button variant="secondary" onClick={() => notify("Exportación simulada")}><Download /> Exportar</Button>;

/** Horizontal bar whose width is the hand-written percentage text. */
function Bar({ pct, label }: { pct: string; label: string }) {
  return (
    <div className="flex items-center gap-2" role="img" aria-label={`${label}: ${pct}`}>
      <div className="h-2.5 w-full min-w-24 rounded-full bg-bg-canvas"><div className="h-2.5 rounded-full bg-primary" style={{ width: pct }} /></div>
      <span className="w-10 shrink-0 text-right text-xs tabular-nums text-text-secondary">{pct}</span>
    </div>
  );
}

export function IncomeReport() {
  const [key, setKey] = useState(incomeSets[0]!.key);
  const { status, retry } = useFakeLoad(`income-${key}`, 350);
  const set = incomeSets.find((s) => s.key === key) ?? incomeSets[0]!;
  return (
    <>
      <PageHeader title="Reporte de ingresos" subtitle="Cobros por mes y por concepto." actions={<ExportButton />} />
      <div className="mb-4 flex max-w-md flex-col gap-1">
        <label htmlFor="rango" className="text-sm font-medium text-text-primary">Rango de fechas</label>
        <Select id="rango" value={key} onChange={(e) => setKey(e.target.value)}>{incomeSets.map((s) => <option key={s.key} value={s.key}>{s.label}</option>)}</Select>
      </div>
      <LoadGate status={status} retry={retry}>
        {set.rows.length === 0 ? (
          <Card><EmptyState title="No hubo cobros en este rango" description="Pruebe con otro rango de fechas." action={{ label: "Ver el año lectivo 2026", onAction: () => setKey("year") }} /></Card>
        ) : (
          <div className="flex flex-col gap-4">
            <Card><DetailList items={[["Total cobrado", <Amount value={set.total} strong />], ["Pagos registrados", set.payments], ["Rango", set.label]]} /></Card>
            <Card title="Por mes o período" className="overflow-hidden">
              <MiniTable caption="Ingresos por mes" head={["Período", "Matrícula", "Mensualidades", "Otros", "Total", "Proporción"]} rightFrom={[1, 2, 3, 4]}
                rows={set.rows.map((r) => [r.label, <Amount value={r.enrollment} />, <Amount value={r.tuition} />, <Amount value={r.other} />, <Amount value={r.total} strong />, <div className="w-40"><Bar pct={r.pct} label={r.label} /></div>])} />
            </Card>
            <Card title="Por concepto">
              <MiniTable caption="Ingresos por concepto" head={["Concepto", "Total", "Proporción"]} rightFrom={[1]}
                rows={set.concepts.map((c) => [c.concept, <Amount value={c.total} strong />, <div className="w-48"><Bar pct={c.pct} label={c.concept} /></div>])} />
            </Card>
          </div>
        )}
      </LoadGate>
    </>
  );
}

export function PortfolioReport() {
  const { status, retry } = useFakeLoad("portfolio");
  return (
    <>
      <PageHeader title="Reporte de cartera" subtitle="Saldo por cobrar por tramo de mora y por grado, al 07/11/2026." actions={<ExportButton />} />
      <LoadGate status={status} retry={retry}>
        <div className="flex flex-col gap-4">
          <Card><DetailList items={[["Saldo por cobrar", <Amount value={portfolioTotal.total} strong />], ["Vencido", <Amount value={portfolioTotal.overdue} />], ["Aún sin vencer", <Amount value={portfolioTotal.current} />], ["Alcance", portfolioTotal.students]]} /></Card>
          <Card title="Por tramo de mora">
            <MiniTable caption="Cartera por tramo" head={["Tramo", "Estudiantes", "Monto", "Proporción"]} rightFrom={[1, 2]}
              rows={portfolioBuckets.map((b) => [b.bucket, <span className="tabular-nums">{b.students}</span>, <Amount value={b.amount} strong />, <div className="w-48"><Bar pct={b.pct} label={b.bucket} /></div>])} />
          </Card>
          <Card title="Por grado">
            <MiniTable caption="Cartera por grado" head={["Grado", "Estudiantes", "Sin vencer", "Vencido", "Total", "Proporción"]} rightFrom={[1, 2, 3, 4]}
              rows={portfolioGrades.map((g) => [g.grade, <span className="tabular-nums">{g.students}</span>, <Amount value={g.current} />, <Amount value={g.overdue} />, <Amount value={g.total} strong />, <div className="w-40"><Bar pct={g.pct} label={g.grade} /></div>])} />
          </Card>
        </div>
      </LoadGate>
    </>
  );
}

const diffTone = (d: string) => (d === "L 0.00" ? null : d.startsWith("-") ? <Pill tone="danger">Faltante</Pill> : <Pill tone="primary">Sobrante</Pill>);

export function ClosingReport() {
  const { status, retry } = useFakeLoad("closings");
  const navigate = useNavigate();
  const [diff, setDiff] = useState("");
  const [openId, setOpenId] = useState<string | null>(null);
  const rows = closings.filter((c) => diff === "" || (diff === "with" ? c.difference !== "L 0.00" : c.difference === "L 0.00"));
  const open = closings.find((c) => c.id === openId);
  return (
    <>
      <PageHeader title="Cierres de caja" subtitle="Sesiones de caja cerradas, con el efectivo esperado y el contado." actions={<ExportButton />} />
      <LoadGate status={status} retry={retry}>
        <DataTable
          caption="Cierres de caja" rows={rows} rowKey={(c) => c.id} searchPlaceholder="Buscar por cajero" searchText={(c) => c.cashier}
          filtersActive={diff !== ""} onClearFilters={() => setDiff("")}
          filters={<Select aria-label="Diferencia" value={diff} onChange={(e) => setDiff(e.target.value)} className="w-48"><option value="">Todas</option><option value="with">Con diferencia</option><option value="without">Sin diferencia</option></Select>}
          emptyTitle="Aún no hay cierres de caja" emptyDescription="Los cierres aparecen cuando un cajero cierra su sesión." emptyAction={{ label: "Ir a caja", onAction: () => void navigate({ to: "/caja" }) }}
          columns={[
            { header: "Cajero", render: (c) => <button type="button" onClick={() => setOpenId(c.id)} className="font-medium text-text-link hover:underline">{c.cashier}</button> },
            { header: "Apertura", render: (c) => <span className="tabular-nums">{c.opened}</span> },
            { header: "Cierre", render: (c) => <span className="tabular-nums">{c.closed}</span> },
            { header: "Esperado", align: "right", render: (c) => <Amount value={c.expected} /> },
            { header: "Contado", align: "right", render: (c) => <Amount value={c.counted} /> },
            { header: "Diferencia", align: "right", render: (c) => <span className="inline-flex items-center justify-end gap-2">{diffTone(c.difference)}<Amount value={c.difference} strong={c.difference !== "L 0.00"} /></span> },
          ]}
        />
      </LoadGate>
      {open ? (
        <Drawer title={`Cierre de ${open.cashier}`} onClose={() => setOpenId(null)}>
          <div className="flex flex-col gap-5">
            <DetailList items={[["Apertura", <span className="tabular-nums">{open.opened}</span>], ["Cierre", <span className="tabular-nums">{open.closed}</span>], ["Fondo inicial", <Amount value={open.opening} />], ["Pagos registrados", open.payments], ["Total cobrado", <Amount value={open.collected} strong />], ["Efectivo", <Amount value={open.cash} />], ["Transferencias", <Amount value={open.transfers} />], ["Tarjetas", <Amount value={open.cards} />]]} />
            <Card title="Arqueo">
              <DetailList items={[["Efectivo esperado", <Amount value={open.expected} />], ["Efectivo contado", <Amount value={open.counted} />], ["Diferencia", <span className="inline-flex items-center gap-2">{diffTone(open.difference)}<Amount value={open.difference} strong /></span>]]} />
              <p className="mt-4 text-sm text-text-secondary">Observación del cajero: {open.note}</p>
            </Card>
          </div>
        </Drawer>
      ) : null}
    </>
  );
}
