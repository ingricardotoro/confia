import { Link } from "@tanstack/react-router";
import { Button, Skeleton } from "@confia/ui";
import { HandCoins } from "lucide-react";
import { Amount, Card, LoadGate, PageHeader } from "../lib/ui";
import { dashboard, CURRENT_USER } from "../mock/data";
import { useFakeLoad } from "../mock/store";

export function Dashboard() {
  const { status, retry } = useFakeLoad("dashboard");
  return (
    <>
      <PageHeader
        title={`Buenos días, ${CURRENT_USER.name.split(" ")[0]}`}
        subtitle="Resumen de la cobranza al 7 de noviembre de 2026."
        actions={<Link to="/pagos/nuevo"><Button><HandCoins /> Registrar pago</Button></Link>}
      />
      <LoadGate status={status} retry={retry} skeleton={<div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">{[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-28" />)}</div>}>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {dashboard.kpis.map((k) => (
            <Card key={k.label}>
              <p className="text-sm text-text-secondary">{k.label}</p>
              <p className="mt-1 text-2xl font-semibold text-text-primary"><Amount value={k.value} /></p>
              <p className="mt-1 text-xs text-text-tertiary">{k.hint}</p>
            </Card>
          ))}
        </div>
        <Card title="Actividad reciente" className="mt-6">
          <ul className="divide-y divide-border-subtle">
            {dashboard.activity.map((a) => (
              <li key={a.id} className="flex flex-wrap items-baseline justify-between gap-2 py-3 text-sm">
                <span className="text-text-primary">{a.text}</span>
                <time className="text-text-secondary tabular-nums">{a.at}</time>
              </li>
            ))}
          </ul>
        </Card>
      </LoadGate>
    </>
  );
}
