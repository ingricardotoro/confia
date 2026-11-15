// Fictitious data for the prototype. Every amount is a pre-formatted display string authored by hand:
// nothing in this app adds, multiplies or rounds money.
export type Status = "paid" | "pending" | "partial" | "overdue" | "void" | "promised" | "refunded";

export const ROLES = ["Super Admin", "Administrador", "Cajero", "Contabilidad", "Auditor", "Coordinador Académico"] as const;
export type Role = (typeof ROLES)[number];

export interface Org {
  id: string; name: string; rtn: string; kind: string; city: string; director: string;
  phone: string; email: string; active: boolean; studentCount: string;
}
export interface StaffUser {
  id: string; name: string; email: string; role: Role; mfa: boolean; active: boolean; lastAccess: string;
}
export interface Charge {
  id: string; concept: string; due: string; amount: string; status: Status;
}
export interface Student {
  id: string; code: string; name: string; grade: string; section: string; active: boolean;
  balance: string; balanceStatus: Status; balanceStale?: boolean; guardianIds: string[]; charges: Charge[];
  enrolledOn: string; shift: string;
}
export interface Guardian {
  id: string; name: string; relation: string; phone: string; email: string; idNumber: string;
  address: string; studentIds: string[];
}
export interface Payment {
  id: string; number: string; studentId: string; date: string; method: string; reference: string;
  amount: string; status: Status; cashier: string; concepts: string[];
}
export interface Invoice {
  id: string; correlative: string; cai: string; date: string; client: string; total: string; status: Status; paymentId: string;
}
export interface CashSession {
  id: string; cashier: string; opened: string; closed: string | null; opening: string; collected: string;
  cash: string; transfers: string; cards: string; expected: string; counted: string; difference: string;
}

export const INSTITUTION = "Colegio Bilingüe Los Pinos";
export const CURRENT_USER = { name: "Marlene Cáceres", role: "Administrador" as Role, email: "marlene.caceres@lospinos.example" };

export const GRADES = ["Preparatoria", "1.º grado", "2.º grado", "3.º grado", "4.º grado", "5.º grado", "6.º grado", "7.º grado"];
export const SECTIONS = ["A", "B"];
export const PAYMENT_METHODS = ["Efectivo", "Transferencia bancaria", "Tarjeta de débito", "Tarjeta de crédito", "Cheque"];

export const orgs: Org[] = [
  { id: "o1", name: "Colegio Bilingüe Los Pinos", rtn: "08019999123456", kind: "Colegio privado", city: "Tegucigalpa", director: "Lic. Rosa Elena Almendárez", phone: "+504 2222-0101", email: "direccion@lospinos.example", active: true, studentCount: "412" },
  { id: "o2", name: "Escuela Cristiana Monte Sión", rtn: "05019999654321", kind: "Escuela confesional", city: "San Pedro Sula", director: "Lic. Gustavo Meléndez", phone: "+504 2550-0202", email: "info@montesion.example", active: true, studentCount: "238" },
  { id: "o3", name: "Instituto Técnico Luz del Saber", rtn: "01019999112233", kind: "Instituto técnico", city: "La Ceiba", director: "Ing. Daniela Fúnez", phone: "+504 2440-0303", email: "admin@luzdelsaber.example", active: true, studentCount: "305" },
  { id: "o4", name: "Academia Nuevo Amanecer", rtn: "04019999445566", kind: "Academia", city: "Comayagua", director: "Lic. Héctor Zelaya", phone: "+504 2772-0404", email: "contacto@nuevoamanecer.example", active: false, studentCount: "96" },
];

export const staff: StaffUser[] = [
  { id: "u1", name: "Marlene Cáceres", email: "marlene.caceres@lospinos.example", role: "Administrador", mfa: true, active: true, lastAccess: "07/11/2026 08:12" },
  { id: "u2", name: "Iván Portillo", email: "ivan.portillo@lospinos.example", role: "Super Admin", mfa: true, active: true, lastAccess: "06/11/2026 17:40" },
  { id: "u3", name: "Karla Mejía", email: "karla.mejia@lospinos.example", role: "Cajero", mfa: true, active: true, lastAccess: "07/11/2026 07:55" },
  { id: "u4", name: "Oscar Valladares", email: "oscar.valladares@lospinos.example", role: "Cajero", mfa: false, active: true, lastAccess: "05/11/2026 15:20" },
  { id: "u5", name: "Teresa Bonilla", email: "teresa.bonilla@lospinos.example", role: "Contabilidad", mfa: true, active: true, lastAccess: "06/11/2026 11:03" },
  { id: "u6", name: "Néstor Reyes", email: "nestor.reyes@lospinos.example", role: "Auditor", mfa: true, active: true, lastAccess: "30/10/2026 09:30" },
  { id: "u7", name: "Alma Sierra", email: "alma.sierra@lospinos.example", role: "Coordinador Académico", mfa: false, active: true, lastAccess: "04/11/2026 13:48" },
  { id: "u8", name: "Rigoberto Flores", email: "rigoberto.flores@lospinos.example", role: "Cajero", mfa: false, active: false, lastAccess: "12/08/2026 10:15" },
];

// Hand-authored tiers: the sum of N monthly charges is written down, never computed.
const TIER = {
  a: { monthly: "L 2,350.00", sums: ["L 0.00", "L 2,350.00", "L 4,700.00", "L 7,050.00"] },
  b: { monthly: "L 2,800.00", sums: ["L 0.00", "L 2,800.00", "L 5,600.00", "L 8,400.00"] },
};
const MONTHS = ["febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre"];
const DUE = ["05/02", "05/03", "05/04", "05/05", "05/06", "05/07", "05/08", "05/09", "05/10", "05/11"];

function mkStudent(i: number, code: string, name: string, gi: number, sec: string, tier: "a" | "b", pendingN: 0 | 1 | 2 | 3, guardianIds: string[], shift = "Matutina"): Student {
  const t = TIER[tier];
  const charges: Charge[] = [{ id: `c${i}-m`, concept: "Matrícula 2026", due: "15/01/2026", amount: "L 1,500.00", status: "paid" }];
  MONTHS.forEach((m, k) => {
    const isPending = k >= MONTHS.length - pendingN;
    const status: Status = !isPending ? "paid" : k === MONTHS.length - 1 ? "pending" : "overdue";
    charges.push({ id: `c${i}-${k}`, concept: `Mensualidad ${m}`, due: `${DUE[k]}/2026`, amount: t.monthly, status });
  });
  const bal: Status = pendingN === 0 ? "paid" : pendingN === 1 ? "pending" : "overdue";
  return { id: `s${i}`, code, name, grade: GRADES[gi]!, section: sec, active: true, balance: t.sums[pendingN]!, balanceStatus: bal, guardianIds, charges, enrolledOn: "15/01/2026", shift };
}

export const students: Student[] = [
  mkStudent(1, "E-2026-0101", "Camila Fernanda Rápalo Soto", 0, "A", "a", 0, ["g1"]),
  mkStudent(2, "E-2026-0102", "Mateo Andrés Rápalo Soto", 2, "A", "a", 1, ["g1"]),
  mkStudent(3, "E-2026-0103", "Valeria Isabel Cruz Aguilar", 1, "B", "a", 0, ["g2"]),
  mkStudent(4, "E-2026-0104", "Sebastián José Matute Ortiz", 3, "A", "a", 2, ["g3", "g4"]),
  mkStudent(5, "E-2026-0105", "Daniela Sofía Lagos Pineda", 4, "B", "b", 0, ["g5"]),
  mkStudent(6, "E-2026-0106", "Emilio David Zúniga Paz", 4, "A", "b", 3, ["g6"], "Vespertina"),
  mkStudent(7, "E-2026-0107", "Ximena Alejandra Bardales Rivera", 5, "A", "b", 1, ["g7"]),
  mkStudent(8, "E-2026-0108", "Joaquín Ernesto Villeda Cano", 5, "B", "b", 0, ["g8"]),
  mkStudent(9, "E-2026-0109", "Renata Michelle Escoto Lanza", 6, "A", "b", 2, ["g9"], "Vespertina"),
  mkStudent(10, "E-2026-0110", "Gael Rodrigo Pavón Medina", 6, "B", "b", 1, ["g10"]),
  mkStudent(11, "E-2026-0111", "Luciana Beatriz Ardón Ponce", 7, "A", "b", 0, ["g2", "g5"]),
  mkStudent(12, "E-2026-0112", "Thiago Samuel Maradiaga Euceda", 0, "B", "a", 1, ["g4"]),
  mkStudent(13, "E-2026-0113", "Antonella Paola Discua Funes", 1, "A", "a", 3, ["g3"], "Vespertina"),
  mkStudent(14, "E-2026-0114", "Dylan Mauricio Calix Barahona", 3, "B", "a", 0, ["g6"]),
];

const MASK = "0801-••••-•••••";
export const guardians: Guardian[] = [
  { id: "g1", name: "Patricia Soto de Rápalo", relation: "Madre", phone: "+504 9900-1101", email: "patricia.soto@correo.example", idNumber: MASK, address: "Col. Los Laureles, Tegucigalpa", studentIds: ["s1", "s2"] },
  { id: "g2", name: "Ricardo Cruz Padilla", relation: "Padre", phone: "+504 9900-1102", email: "ricardo.cruz@correo.example", idNumber: MASK, address: "Res. El Trapiche, Tegucigalpa", studentIds: ["s3", "s11"] },
  { id: "g3", name: "Sonia Ortiz de Matute", relation: "Madre", phone: "+504 9900-1103", email: "sonia.ortiz@correo.example", idNumber: MASK, address: "Col. Kennedy, Tegucigalpa", studentIds: ["s4", "s13"] },
  { id: "g4", name: "Jorge Matute Banegas", relation: "Padre", phone: "+504 9900-1104", email: "jorge.matute@correo.example", idNumber: MASK, address: "Col. Kennedy, Tegucigalpa", studentIds: ["s4", "s12"] },
  { id: "g5", name: "Lourdes Pineda Alvarado", relation: "Tutora legal", phone: "+504 9900-1105", email: "lourdes.pineda@correo.example", idNumber: MASK, address: "Col. Miraflores, Tegucigalpa", studentIds: ["s5", "s11"] },
  { id: "g6", name: "Wilfredo Zúniga Rueda", relation: "Padre", phone: "+504 9900-1106", email: "wilfredo.zuniga@correo.example", idNumber: MASK, address: "Col. Palmira, Tegucigalpa", studentIds: ["s6", "s14"] },
  { id: "g7", name: "Marisol Rivera de Bardales", relation: "Madre", phone: "+504 9900-1107", email: "marisol.rivera@correo.example", idNumber: MASK, address: "Lomas del Guijarro, Tegucigalpa", studentIds: ["s7"] },
  { id: "g8", name: "Alfredo Villeda Moncada", relation: "Padre", phone: "+504 9900-1108", email: "alfredo.villeda@correo.example", idNumber: MASK, address: "Col. Humuya, Tegucigalpa", studentIds: ["s8"] },
  { id: "g9", name: "Karen Lanza de Escoto", relation: "Madre", phone: "+504 9900-1109", email: "karen.lanza@correo.example", idNumber: MASK, address: "Col. Florencia, Tegucigalpa", studentIds: ["s9"] },
  { id: "g10", name: "Misael Pavón Gálvez", relation: "Padre", phone: "+504 9900-1110", email: "misael.pavon@correo.example", idNumber: MASK, address: "Col. San Miguel, Tegucigalpa", studentIds: ["s10"] },
];

export const payments: Payment[] = [
  { id: "p1", number: "REC-000231", studentId: "s2", date: "07/11/2026 09:14", method: "Efectivo", reference: "-", amount: "L 2,350.00", status: "paid", cashier: "Karla Mejía", concepts: ["Mensualidad septiembre"] },
  { id: "p2", number: "REC-000230", studentId: "s7", date: "07/11/2026 08:47", method: "Transferencia bancaria", reference: "TRF-88412", amount: "L 2,800.00", status: "paid", cashier: "Karla Mejía", concepts: ["Mensualidad octubre"] },
  { id: "p3", number: "REC-000229", studentId: "s5", date: "06/11/2026 16:20", method: "Tarjeta de débito", reference: "AUT-5521", amount: "L 2,800.00", status: "paid", cashier: "Oscar Valladares", concepts: ["Mensualidad noviembre"] },
  { id: "p4", number: "REC-000228", studentId: "s9", date: "06/11/2026 14:05", method: "Efectivo", reference: "-", amount: "L 1,400.00", status: "partial", cashier: "Karla Mejía", concepts: ["Mensualidad octubre (abono)"] },
  { id: "p5", number: "REC-000227", studentId: "s3", date: "06/11/2026 10:31", method: "Transferencia bancaria", reference: "TRF-88377", amount: "L 2,350.00", status: "paid", cashier: "Karla Mejía", concepts: ["Mensualidad noviembre"] },
  { id: "p6", number: "REC-000226", studentId: "s14", date: "05/11/2026 15:12", method: "Cheque", reference: "CHQ-004512", amount: "L 2,350.00", status: "paid", cashier: "Oscar Valladares", concepts: ["Mensualidad noviembre"] },
  { id: "p7", number: "REC-000225", studentId: "s8", date: "05/11/2026 11:48", method: "Efectivo", reference: "-", amount: "L 2,800.00", status: "paid", cashier: "Karla Mejía", concepts: ["Mensualidad noviembre"] },
  { id: "p8", number: "REC-000224", studentId: "s1", date: "04/11/2026 09:02", method: "Tarjeta de crédito", reference: "AUT-5480", amount: "L 2,350.00", status: "void", cashier: "Karla Mejía", concepts: ["Mensualidad noviembre"] },
  { id: "p9", number: "REC-000223", studentId: "s11", date: "03/11/2026 13:30", method: "Transferencia bancaria", reference: "TRF-88290", amount: "L 2,800.00", status: "paid", cashier: "Oscar Valladares", concepts: ["Mensualidad noviembre"] },
  { id: "p10", number: "REC-000222", studentId: "s6", date: "02/11/2026 10:10", method: "Efectivo", reference: "-", amount: "L 2,800.00", status: "partial", cashier: "Karla Mejía", concepts: ["Mensualidad agosto"] },
];

const CAI = "7F3A1C-2B9D4E-6A8F05-C1D7E3-9B2A4F-8E";
export const invoices: Invoice[] = [
  { id: "f1", correlative: "000-001-01-00001041", cai: CAI, date: "07/11/2026", client: "Patricia Soto de Rápalo", total: "L 2,350.00", status: "paid", paymentId: "p1" },
  { id: "f2", correlative: "000-001-01-00001040", cai: CAI, date: "07/11/2026", client: "Marisol Rivera de Bardales", total: "L 2,800.00", status: "paid", paymentId: "p2" },
  { id: "f3", correlative: "000-001-01-00001039", cai: CAI, date: "06/11/2026", client: "Lourdes Pineda Alvarado", total: "L 2,800.00", status: "paid", paymentId: "p3" },
  { id: "f4", correlative: "000-001-01-00001038", cai: CAI, date: "06/11/2026", client: "Karen Lanza de Escoto", total: "L 1,400.00", status: "partial", paymentId: "p4" },
  { id: "f5", correlative: "000-001-01-00001037", cai: CAI, date: "04/11/2026", client: "Patricia Soto de Rápalo", total: "L 2,350.00", status: "void", paymentId: "p8" },
];

export const cashSessions: CashSession[] = [
  { id: "k3", cashier: "Karla Mejía", opened: "07/11/2026 07:30", closed: null, opening: "L 1,000.00", collected: "L 5,150.00", cash: "L 2,350.00", transfers: "L 2,800.00", cards: "L 0.00", expected: "L 3,350.00", counted: "-", difference: "-" },
  { id: "k2", cashier: "Oscar Valladares", opened: "06/11/2026 07:45", closed: "06/11/2026 17:05", opening: "L 1,000.00", collected: "L 4,800.00", cash: "L 0.00", transfers: "L 2,350.00", cards: "L 2,450.00", expected: "L 1,000.00", counted: "L 1,000.00", difference: "L 0.00" },
  { id: "k1", cashier: "Karla Mejía", opened: "05/11/2026 07:28", closed: "05/11/2026 16:55", opening: "L 1,000.00", collected: "L 7,950.00", cash: "L 5,600.00", transfers: "L 2,350.00", cards: "L 0.00", expected: "L 6,600.00", counted: "L 6,590.00", difference: "- L 10.00" },
];

export const dashboard = {
  kpis: [
    { label: "Cobrado hoy", value: "L 5,150.00", hint: "2 pagos registrados" },
    { label: "Cobrado en noviembre", value: "L 32,450.00", hint: "10 pagos registrados" },
    { label: "Saldo por cobrar", value: "L 61,550.00", hint: "Estudiantes con saldo: 9" },
    { label: "Saldo vencido", value: "L 48,300.00", hint: "Estudiantes en mora: 6" },
  ],
  activity: [
    { id: "a1", at: "07/11/2026 09:14", text: "Karla Mejía registró el pago REC-000231 de Mateo Rápalo." },
    { id: "a2", at: "07/11/2026 08:47", text: "Karla Mejía registró el pago REC-000230 de Ximena Bardales." },
    { id: "a3", at: "07/11/2026 07:30", text: "Karla Mejía abrió la caja del día." },
    { id: "a4", at: "06/11/2026 17:40", text: "Iván Portillo cambió el rol de Alma Sierra." },
    { id: "a5", at: "06/11/2026 17:05", text: "Oscar Valladares cerró la caja del día." },
  ],
};
