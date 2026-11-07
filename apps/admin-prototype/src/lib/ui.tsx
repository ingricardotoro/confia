import { Link } from "@tanstack/react-router";
import { Alert, Button, EmptyState, ErrorState, Input, Skeleton, StatusBadge } from "@confia/ui";
import { ChevronRight, Search, X } from "lucide-react";
import { useEffect, useState, type ComponentProps, type ReactNode } from "react";
import type { Status } from "../mock/data";

export const selectClass =
  "h-10 w-full rounded-md border border-(--input-border) bg-(--input-bg) px-3 text-sm text-(--input-fg) focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus";

export function Select({ className, ...rest }: ComponentProps<"select">) {
  return <select {...rest} className={`${selectClass} ${className ?? ""}`} />;
}

/** Amounts arrive pre-formatted from the mock data; this only aligns them. No arithmetic anywhere. */
export function Amount({ value, strong = false }: { value: string; strong?: boolean }) {
  return <span className={`whitespace-nowrap tabular-nums ${strong ? "font-semibold text-text-primary" : ""}`}>{value}</span>;
}

/** Inserts thousands separators into typed digits with string operations only. */
export function displayTyped(text: string): string {
  const clean = text.trim().replace(/^L\s*/i, "").replace(/,/g, "");
  const [whole = "0", frac = ""] = clean.split(".");
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `L ${grouped}.${(frac + "00").slice(0, 2)}`;
}
export const isTypedAmount = (t: string) => /^\s*(L\s*)?\d{1,9}(,\d{3})*(\.\d{1,2})?\s*$/i.test(t) && /[1-9]/.test(t);

export function PageHeader({ title, subtitle, crumbs, actions }: { title: string; subtitle?: string; crumbs?: { label: string; to?: string }[]; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
      <div className="flex flex-col gap-1">
        {crumbs ? (
          <nav aria-label="Migas de pan" className="flex items-center gap-1 text-sm text-text-secondary">
            {crumbs.map((c, i) => (
              <span key={c.label} className="flex items-center gap-1">
                {i > 0 ? <ChevronRight className="size-3.5" aria-hidden="true" /> : null}
                {c.to ? <Link to={c.to} className="hover:underline">{c.label}</Link> : <span aria-current="page">{c.label}</span>}
              </span>
            ))}
          </nav>
        ) : null}
        <h1 className="text-2xl font-semibold text-text-primary">{title}</h1>
        {subtitle ? <p className="text-sm text-text-secondary">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  );
}

export function Card({ title, children, actions, className = "" }: { title?: string; children: ReactNode; actions?: ReactNode; className?: string }) {
  return (
    <section className={`rounded-lg border border-border-subtle bg-bg-surface shadow-sm ${className}`}>
      {title ? (
        <header className="flex items-center justify-between gap-2 border-b border-border-subtle px-5 py-3">
          <h2 className="text-base font-semibold text-text-primary">{title}</h2>
          {actions}
        </header>
      ) : null}
      <div className="p-5">{children}</div>
    </section>
  );
}

export function DetailList({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="grid gap-x-8 gap-y-4 sm:grid-cols-2">
      {items.map(([k, v]) => (
        <div key={k} className="flex flex-col gap-0.5">
          <dt className="text-sm text-text-secondary">{k}</dt>
          <dd className="text-base text-text-primary">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

export function PageSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <div aria-busy="true" aria-label="Cargando" className="flex flex-col gap-3">
      <Skeleton className="h-10 w-72" />
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-12 w-full" />
      ))}
    </div>
  );
}

/** Renders the skeleton while loading, the error state with retry on failure, and the content when ready. */
export function LoadGate({ status, retry, children, skeleton }: { status: "loading" | "ready" | "error"; retry: () => void; children: ReactNode; skeleton?: ReactNode }) {
  if (status === "loading") return <>{skeleton ?? <PageSkeleton />}</>;
  if (status === "error")
    return (
      <Card>
        <ErrorState title="No pudimos cargar la información" description="Hubo un problema de conexión. Sus datos no han cambiado. Intente de nuevo." onRetry={retry} />
      </Card>
    );
  return <>{children}</>;
}

export function Toast({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <div className="fixed right-6 bottom-6 z-50 max-w-sm shadow-lg">
      <Alert variant="info">{message}</Alert>
    </div>
  );
}

export function Modal({ title, children, onClose, footer }: { title: string; children: ReactNode; onClose: () => void; footer: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div role="dialog" aria-modal="true" aria-label={title} className="w-full max-w-md rounded-lg bg-bg-surface-elevated p-6 shadow-xl">
        <h2 className="mb-2 text-lg font-semibold text-text-primary">{title}</h2>
        <div className="mb-6 text-sm text-text-secondary">{children}</div>
        <div className="flex justify-end gap-2">{footer}</div>
      </div>
    </div>
  );
}

export function Tabs({ tabs, value, onChange }: { tabs: { id: string; label: string }[]; value: string; onChange: (id: string) => void }) {
  return (
    <div role="tablist" className="mb-4 flex gap-1 border-b border-border-subtle">
      {tabs.map((t) => (
        <button
          key={t.id}
          role="tab"
          type="button"
          aria-selected={value === t.id}
          onClick={() => onChange(t.id)}
          className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium focus-visible:outline-2 focus-visible:outline-border-focus ${value === t.id ? "border-primary text-primary" : "border-transparent text-text-secondary hover:text-text-primary"}`}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

export const StatusFor = ({ status }: { status: Status }) => <StatusBadge status={status} size="sm" />;

export interface Column<T> {
  header: string;
  render: (row: T) => ReactNode;
  align?: "right";
}

interface DataTableProps<T> {
  rows: T[];
  columns: Column<T>[];
  rowKey: (row: T) => string;
  searchText: (row: T) => string;
  searchPlaceholder: string;
  filters?: ReactNode;
  filtersActive?: boolean;
  onClearFilters?: () => void;
  emptyTitle: string;
  emptyDescription: string;
  emptyAction: { label: string; onAction: () => void };
  caption: string;
  pageSize?: number;
}

export function DataTable<T>({ rows, columns, rowKey, searchText, searchPlaceholder, filters, filtersActive, onClearFilters, emptyTitle, emptyDescription, emptyAction, caption, pageSize = 8 }: DataTableProps<T>) {
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const needle = q.trim().toLowerCase();
  const found = needle ? rows.filter((r) => searchText(r).toLowerCase().includes(needle)) : rows;
  const pages = Math.max(1, Math.ceil(found.length / pageSize));
  const current = Math.min(page, pages - 1);
  const slice = found.slice(current * pageSize, current * pageSize + pageSize);
  const refined = needle !== "" || filtersActive === true;

  return (
    <Card className="overflow-hidden p-0">
      <div className="flex flex-wrap items-end gap-3 border-b border-border-subtle p-4">
        <div className="relative w-full max-w-sm">
          <Search className="pointer-events-none absolute top-3 left-3 size-4 text-text-tertiary" aria-hidden="true" />
          <Input aria-label={searchPlaceholder} placeholder={searchPlaceholder} value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} className="pl-9" />
        </div>
        {filters}
        {refined ? (
          <Button variant="subtle" size="sm" onClick={() => { setQ(""); onClearFilters?.(); }}>
            <X /> Limpiar
          </Button>
        ) : null}
      </div>
      {found.length === 0 ? (
        refined ? (
          <EmptyState title="Sin resultados" description="Ningún registro coincide con su búsqueda o sus filtros. Pruebe con otros criterios." action={{ label: "Limpiar búsqueda y filtros", onAction: () => { setQ(""); onClearFilters?.(); } }} />
        ) : (
          <EmptyState title={emptyTitle} description={emptyDescription} action={emptyAction} />
        )
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <caption className="sr-only">{caption}</caption>
              <thead className="bg-bg-canvas text-text-secondary">
                <tr>
                  {columns.map((c) => (
                    <th key={c.header} scope="col" className={`px-4 py-3 font-medium ${c.align === "right" ? "text-right" : "text-left"}`}>{c.header}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-border-subtle">
                {slice.map((r) => (
                  <tr key={rowKey(r)} className="hover:bg-bg-canvas">
                    {columns.map((c) => (
                      <td key={c.header} className={`px-4 py-3 ${c.align === "right" ? "text-right" : "text-left"}`}>{c.render(r)}</td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex items-center justify-between border-t border-border-subtle px-4 py-3 text-sm text-text-secondary">
            <span>
              Mostrando {current * pageSize + 1} a {current * pageSize + slice.length} de {found.length}
            </span>
            <div className="flex items-center gap-2">
              <Button variant="secondary" size="sm" disabled={current === 0} onClick={() => setPage(current - 1)}>Anterior</Button>
              <span aria-live="polite">Página {current + 1} de {pages}</span>
              <Button variant="secondary" size="sm" disabled={current >= pages - 1} onClick={() => setPage(current + 1)}>Siguiente</Button>
            </div>
          </div>
        </>
      )}
    </Card>
  );
}
