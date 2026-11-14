import { useEffect, useState, useSyncExternalStore } from "react";
import * as seed from "./data";
import * as cfg from "./config-data";
import type { Fee, Grade, Period, Scholarship, StudentScholarship } from "./config-data";
import * as col from "./collections-data";
import * as por from "./portal-data";
import type { PaymentReport } from "./portal-data";
import type { PaymentPromise, PromiseStatus, Reminder } from "./collections-data";
import type { CashSession, Guardian, Invoice, Org, Payment, StaffUser, Student } from "./data";

// In-memory store: a new state object is created on every write, so selectors that return an
// existing array or object stay referentially stable between writes.
interface State {
  orgs: Org[]; staff: StaffUser[]; students: Student[]; guardians: Guardian[];
  payments: Payment[]; invoices: Invoice[]; cashSessions: CashSession[];
  periods: Period[]; grades: Grade[]; fees: Fee[]; scholarships: Scholarship[]; studentScholarships: StudentScholarship[];
  promises: PaymentPromise[]; reminders: Reminder[]; paymentReports: PaymentReport[];
  signedIn: boolean; simulateError: boolean; toast: string | null;
}

let state: State = {
  orgs: seed.orgs, staff: seed.staff, students: seed.students, guardians: seed.guardians,
  payments: seed.payments, invoices: seed.invoices, cashSessions: seed.cashSessions,
  periods: cfg.periods, grades: cfg.grades, fees: cfg.fees, scholarships: cfg.scholarships, studentScholarships: cfg.studentScholarships,
  promises: col.promises, reminders: col.reminders, paymentReports: por.paymentReports,
  signedIn: false, simulateError: false, toast: null,
};
const listeners = new Set<() => void>();
function set(patch: Partial<State>) {
  state = { ...state, ...patch };
  listeners.forEach((l) => l());
}
export function useStore<T>(select: (s: State) => T): T {
  return useSyncExternalStore(
    (l) => { listeners.add(l); return () => listeners.delete(l); },
    () => select(state),
  );
}

let seq = 100;
export const nextId = (p: string) => `${p}${++seq}`;

function upsert<T extends { id: string }>(list: T[], item: T): T[] {
  return list.some((x) => x.id === item.id) ? list.map((x) => (x.id === item.id ? item : x)) : [item, ...list];
}

let toastTimer: ReturnType<typeof setTimeout> | undefined;
export function notify(message: string) {
  set({ toast: message });
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => set({ toast: null }), 4000);
}

export const actions = {
  addPaymentReport: (r: PaymentReport) => set({ paymentReports: [r, ...state.paymentReports] }),
  savePromise: (p: PaymentPromise) => set({ promises: upsert(state.promises, p) }),
  setPromiseStatus: (id: string, status: PromiseStatus) => set({ promises: state.promises.map((p) => (p.id === id ? { ...p, status } : p)) }),
  addReminder: (r: Reminder) => set({ reminders: [r, ...state.reminders] }),
  savePeriod: (p: Period) => set({ periods: upsert(state.periods, p) }),
  // Activating a period closes the one that was active, so there is only ever one.
  setActivePeriod: (id: string) =>
    set({ periods: state.periods.map((p) => (p.id === id ? { ...p, status: "Activo" as const } : p.status === "Activo" ? { ...p, status: "Cerrado" as const } : p)) }),
  closePeriod: (id: string) => set({ periods: state.periods.map((p) => (p.id === id ? { ...p, status: "Cerrado" as const } : p)) }),
  saveGrade: (g: Grade) => set({ grades: upsert(state.grades, g) }),
  saveFee: (f: Fee) => set({ fees: upsert(state.fees, f) }),
  saveScholarship: (b: Scholarship) => set({ scholarships: upsert(state.scholarships, b) }),
  assignScholarship: (a: StudentScholarship) => set({ studentScholarships: [...state.studentScholarships, a] }),
  removeScholarship: (studentId: string, scholarshipId: string) =>
    set({ studentScholarships: state.studentScholarships.filter((x) => !(x.studentId === studentId && x.scholarshipId === scholarshipId)) }),
  signIn: () => set({ signedIn: true }),
  signOut: () => set({ signedIn: false }),
  setSimulateError: (v: boolean) => set({ simulateError: v }),
  saveOrg: (o: Org) => set({ orgs: upsert(state.orgs, o) }),
  saveStaff: (u: StaffUser) => set({ staff: upsert(state.staff, u) }),
  saveStudent: (s: Student) => set({ students: upsert(state.students, s) }),
  saveGuardian: (g: Guardian) => {
    set({
      guardians: upsert(state.guardians, g),
      students: state.students.map((s) => {
        const linked = g.studentIds.includes(s.id);
        const has = s.guardianIds.includes(g.id);
        if (linked && !has) return { ...s, guardianIds: [...s.guardianIds, g.id] };
        if (!linked && has) return { ...s, guardianIds: s.guardianIds.filter((x) => x !== g.id) };
        return s;
      }),
    });
  },
  // A payment marks the selected charges as paid. The new balance is the server's job: when charges
  // remain open the student shows "balance being recalculated" instead of a browser-made figure.
  registerPayment: (p: Payment, settledChargeIds: string[]) => {
    const students = state.students.map((s) => {
      if (s.id !== p.studentId) return s;
      const charges = s.charges.map((c) => (settledChargeIds.includes(c.id) ? { ...c, status: "paid" as const } : c));
      const open = charges.some((c) => c.status !== "paid");
      return open
        ? { ...s, charges, balanceStale: true }
        : { ...s, charges, balance: "L 0.00", balanceStatus: "paid" as const, balanceStale: false };
    });
    set({ students, payments: [p, ...state.payments] });
  },
  voidPayment: (id: string) =>
    set({ payments: state.payments.map((p) => (p.id === id ? { ...p, status: "void" as const } : p)) }),
  openCash: (s: CashSession) => set({ cashSessions: [s, ...state.cashSessions] }),
  closeCash: (id: string, closedAt: string) =>
    set({ cashSessions: state.cashSessions.map((c) => (c.id === id ? { ...c, closed: closedAt, counted: c.expected, difference: "L 0.00" } : c)) }),
};

/** Fake loading: "loading" for a short delay, then "ready" or "error" when the demo toggle is on. */
export function useFakeLoad(key: string, delay = 450) {
  const [attempt, setAttempt] = useState(0);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  useEffect(() => {
    setStatus("loading");
    const t = setTimeout(() => setStatus(state.simulateError ? "error" : "ready"), delay);
    return () => clearTimeout(t);
  }, [key, attempt, delay]);
  return { status, retry: () => setAttempt((a) => a + 1) };
}
