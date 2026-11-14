// Collections mock data. Amounts, days and counts are hand-written display text; nothing is computed.
export const BUCKETS = ["1 a 30 días", "31 a 60 días", "61 a 90 días", "Más de 90 días"] as const;
export type Bucket = (typeof BUCKETS)[number];
export const CONTACT_STATUSES = ["Sin contacto", "Contactado", "Promesa vigente", "Incumplida"] as const;
export type ContactStatus = (typeof CONTACT_STATUSES)[number];
export const CHANNELS = ["Llamada", "WhatsApp", "Presencial", "Correo"] as const;

export interface DebtorCharge { concept: string; due: string; days: string; amount: string }
export interface ContactEntry { at: string; channel: string; by: string; note: string }
export interface Debtor {
  id: string; studentId: string; student: string; grade: string; guardian: string; phone: string;
  days: string; bucket: Bucket; overdue: string; lastContact: string; status: ContactStatus;
  charges: DebtorCharge[]; history: ContactEntry[];
}

export const debtors: Debtor[] = [
  { id: "m1", studentId: "s13", student: "Antonella Paola Discua Funes", grade: "1.º grado", guardian: "Sonia Ortiz de Matute", phone: "+504 9900-1103", days: "125 días", bucket: "Más de 90 días", overdue: "L 7,050.00", lastContact: "Sin contacto", status: "Sin contacto",
    charges: [{ concept: "Mensualidad julio", due: "05/07/2026", days: "125 días", amount: "L 2,350.00" }, { concept: "Mensualidad agosto", due: "05/08/2026", days: "94 días", amount: "L 2,350.00" }, { concept: "Mensualidad septiembre", due: "05/09/2026", days: "63 días", amount: "L 2,350.00" }],
    history: [] },
  { id: "m2", studentId: "s6", student: "Emilio David Zúniga Paz", grade: "4.º grado", guardian: "Wilfredo Zúniga Rueda", phone: "+504 9900-1106", days: "63 días", bucket: "61 a 90 días", overdue: "L 8,400.00", lastContact: "03/11/2026", status: "Contactado",
    charges: [{ concept: "Mensualidad septiembre", due: "05/09/2026", days: "63 días", amount: "L 2,800.00" }, { concept: "Mensualidad octubre", due: "05/10/2026", days: "33 días", amount: "L 2,800.00" }, { concept: "Mensualidad noviembre", due: "05/11/2026", days: "2 días", amount: "L 2,800.00" }],
    history: [{ at: "03/11/2026 10:20", channel: "Llamada", by: "Teresa Bonilla", note: "Indicó que revisará su situación esta semana." }, { at: "20/10/2026 15:05", channel: "Correo", by: "Sistema", note: "Recordatorio de saldo vencido." }] },
  { id: "m3", studentId: "s4", student: "Sebastián José Matute Ortiz", grade: "3.º grado", guardian: "Jorge Matute Banegas", phone: "+504 9900-1104", days: "33 días", bucket: "31 a 60 días", overdue: "L 4,700.00", lastContact: "05/11/2026", status: "Promesa vigente",
    charges: [{ concept: "Mensualidad octubre", due: "05/10/2026", days: "33 días", amount: "L 2,350.00" }, { concept: "Mensualidad noviembre", due: "05/11/2026", days: "2 días", amount: "L 2,350.00" }],
    history: [{ at: "05/11/2026 09:40", channel: "WhatsApp", by: "Teresa Bonilla", note: "Prometió pagar el 14/11/2026." }] },
  { id: "m4", studentId: "s9", student: "Renata Michelle Escoto Lanza", grade: "6.º grado", guardian: "Karen Lanza de Escoto", phone: "+504 9900-1109", days: "33 días", bucket: "31 a 60 días", overdue: "L 5,600.00", lastContact: "28/10/2026", status: "Incumplida",
    charges: [{ concept: "Mensualidad octubre", due: "05/10/2026", days: "33 días", amount: "L 2,800.00" }, { concept: "Mensualidad noviembre", due: "05/11/2026", days: "2 días", amount: "L 2,800.00" }],
    history: [{ at: "28/10/2026 11:00", channel: "Llamada", by: "Teresa Bonilla", note: "Prometió pagar el 04/11/2026." }, { at: "06/11/2026 08:30", channel: "Sistema", by: "Sistema", note: "La promesa del 04/11/2026 no se cumplió." }] },
  { id: "m5", studentId: "s2", student: "Mateo Andrés Rápalo Soto", grade: "2.º grado", guardian: "Patricia Soto de Rápalo", phone: "+504 9900-1101", days: "2 días", bucket: "1 a 30 días", overdue: "L 2,350.00", lastContact: "Sin contacto", status: "Sin contacto",
    charges: [{ concept: "Mensualidad noviembre", due: "05/11/2026", days: "2 días", amount: "L 2,350.00" }], history: [] },
  { id: "m6", studentId: "s7", student: "Ximena Alejandra Bardales Rivera", grade: "5.º grado", guardian: "Marisol Rivera de Bardales", phone: "+504 9900-1107", days: "2 días", bucket: "1 a 30 días", overdue: "L 2,800.00", lastContact: "06/11/2026", status: "Contactado",
    charges: [{ concept: "Mensualidad noviembre", due: "05/11/2026", days: "2 días", amount: "L 2,800.00" }],
    history: [{ at: "06/11/2026 14:12", channel: "WhatsApp", by: "Teresa Bonilla", note: "Confirmó que pagará esta semana." }] },
  { id: "m7", studentId: "s10", student: "Gael Rodrigo Pavón Medina", grade: "6.º grado", guardian: "Misael Pavón Gálvez", phone: "+504 9900-1110", days: "2 días", bucket: "1 a 30 días", overdue: "L 2,800.00", lastContact: "Sin contacto", status: "Sin contacto",
    charges: [{ concept: "Mensualidad noviembre", due: "05/11/2026", days: "2 días", amount: "L 2,800.00" }], history: [] },
  { id: "m8", studentId: "s12", student: "Thiago Samuel Maradiaga Euceda", grade: "Preparatoria", guardian: "Jorge Matute Banegas", phone: "+504 9900-1104", days: "2 días", bucket: "1 a 30 días", overdue: "L 2,350.00", lastContact: "Sin contacto", status: "Sin contacto",
    charges: [{ concept: "Mensualidad noviembre", due: "05/11/2026", days: "2 días", amount: "L 2,350.00" }], history: [] },
];

export type PromiseStatus = "Vigente" | "Cumplida" | "Incumplida";
export const PROMISE_STATUSES: PromiseStatus[] = ["Vigente", "Cumplida", "Incumplida"];
export interface PaymentPromise {
  id: string; studentId: string; student: string; guardian: string; date: string; amount: string;
  channel: string; notes: string; status: PromiseStatus;
}
export const promises: PaymentPromise[] = [
  { id: "pr1", studentId: "s4", student: "Sebastián José Matute Ortiz", guardian: "Jorge Matute Banegas", date: "14/11/2026", amount: "L 4,700.00", channel: "WhatsApp", notes: "Pagará en caja con efectivo.", status: "Vigente" },
  { id: "pr2", studentId: "s9", student: "Renata Michelle Escoto Lanza", guardian: "Karen Lanza de Escoto", date: "04/11/2026", amount: "L 2,800.00", channel: "Llamada", notes: "Dijo que depositaría a la cuenta del colegio.", status: "Incumplida" },
  { id: "pr3", studentId: "s6", student: "Emilio David Zúniga Paz", guardian: "Wilfredo Zúniga Rueda", date: "30/10/2026", amount: "L 2,800.00", channel: "Presencial", notes: "Abono de la mensualidad de agosto.", status: "Cumplida" },
  { id: "pr4", studentId: "s7", student: "Ximena Alejandra Bardales Rivera", guardian: "Marisol Rivera de Bardales", date: "12/11/2026", amount: "L 2,800.00", channel: "WhatsApp", notes: "", status: "Vigente" },
];

export type ReminderStatus = "Programado" | "Enviado" | "Fallido";
export interface Reminder {
  id: string; channel: string; template: string; audience: string; recipients: string; scheduled: string; status: ReminderStatus;
}
export const TEMPLATES = ["Saldo vencido: primer aviso", "Saldo vencido: segundo aviso", "Recordatorio de vencimiento próximo", "Aviso de promesa de pago"];
export const REMINDER_CHANNELS = ["Correo", "SMS", "WhatsApp"];
export const AUDIENCE_KINDS = ["Todos los morosos", "Por tramo de mora", "Por grado"];
// Hand-written recipient counts for the audience picker (the server resolves them in the real system).
export const RECIPIENTS_BY_BUCKET: Record<string, string> = { "1 a 30 días": "4", "31 a 60 días": "2", "61 a 90 días": "1", "Más de 90 días": "1" };
export const RECIPIENTS_ALL = "8";
export const RECIPIENTS_GRADE = "1";
export const reminders: Reminder[] = [
  { id: "r1", channel: "Correo", template: "Saldo vencido: primer aviso", audience: "Tramo 1 a 30 días", recipients: "4", scheduled: "08/11/2026 08:00", status: "Programado" },
  { id: "r2", channel: "WhatsApp", template: "Aviso de promesa de pago", audience: "Promesas vigentes", recipients: "2", scheduled: "13/11/2026 09:00", status: "Programado" },
  { id: "r3", channel: "SMS", template: "Saldo vencido: segundo aviso", audience: "Tramo 31 a 60 días", recipients: "2", scheduled: "05/11/2026 08:00", status: "Enviado" },
  { id: "r4", channel: "Correo", template: "Recordatorio de vencimiento próximo", audience: "Todos los morosos", recipients: "8", scheduled: "01/11/2026 08:00", status: "Enviado" },
  { id: "r5", channel: "SMS", template: "Saldo vencido: primer aviso", audience: "Tramo Más de 90 días", recipients: "1", scheduled: "20/10/2026 08:00", status: "Fallido" },
];
