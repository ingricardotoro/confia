// Academic configuration mock data. Amounts and counts are display text; nothing is computed.
import { GRADES } from "./data";

export type PeriodStatus = "Planificado" | "Activo" | "Cerrado";
export interface Period { id: string; name: string; start: string; end: string; status: PeriodStatus }
export interface Section { id: string; name: string; capacity: string; enrolled: string }
export interface Grade { id: string; name: string; sections: Section[] }
export type Frequency = "Única" | "Mensual" | "Por período";
export interface Fee {
  id: string; name: string; frequency: Frequency; dueDay: string; lateFee: string; active: boolean;
  amounts: { grade: string; amount: string }[];
}
export interface Scholarship {
  id: string; name: string; type: "Porcentaje" | "Monto fijo"; value: string; concepts: string[];
  validFrom: string; validTo: string; active: boolean;
}
export interface StudentScholarship { studentId: string; scholarshipId: string; since: string }

export const FREQUENCIES: Frequency[] = ["Única", "Mensual", "Por período"];

export const periods: Period[] = [
  { id: "y1", name: "Año lectivo 2024", start: "15/01/2024", end: "30/11/2024", status: "Cerrado" },
  { id: "y2", name: "Año lectivo 2025", start: "13/01/2025", end: "28/11/2025", status: "Cerrado" },
  { id: "y3", name: "Año lectivo 2026", start: "12/01/2026", end: "27/11/2026", status: "Activo" },
  { id: "y4", name: "Año lectivo 2027", start: "11/01/2027", end: "26/11/2027", status: "Planificado" },
];

const CAPS = ["30", "30", "32", "32", "35", "35", "35", "40"];
const ENR = ["28", "27", "30", "31", "33", "29", "34", "36"];
export const grades: Grade[] = GRADES.map((name, i) => ({
  id: `gr${i + 1}`,
  name,
  sections: [
    { id: `gr${i + 1}-A`, name: "A", capacity: CAPS[i]!, enrolled: ENR[i]! },
    { id: `gr${i + 1}-B`, name: "B", capacity: CAPS[i]!, enrolled: ENR[(i + 3) % 8]! },
  ],
}));

const LOW = GRADES.slice(0, 4);
const HIGH = GRADES.slice(4);
const tiered = (low: string, high: string) => [...LOW.map((g) => ({ grade: g, amount: low })), ...HIGH.map((g) => ({ grade: g, amount: high }))];
const flat = (a: string) => GRADES.map((g) => ({ grade: g, amount: a }));

export const fees: Fee[] = [
  { id: "t1", name: "Matrícula", frequency: "Única", dueDay: "15 de enero", lateFee: "Sin mora", active: true, amounts: flat("L 1,500.00") },
  { id: "t2", name: "Mensualidad", frequency: "Mensual", dueDay: "Día 5 de cada mes", lateFee: "Mora de L 100.00 después del día 10", active: true, amounts: tiered("L 2,350.00", "L 2,800.00") },
  { id: "t3", name: "Transporte", frequency: "Mensual", dueDay: "Día 5 de cada mes", lateFee: "Mora de L 50.00 después del día 10", active: true, amounts: flat("L 650.00") },
  { id: "t4", name: "Uniforme", frequency: "Única", dueDay: "28 de febrero", lateFee: "Sin mora", active: true, amounts: tiered("L 850.00", "L 950.00") },
  { id: "t5", name: "Material didáctico", frequency: "Por período", dueDay: "Primer día del período", lateFee: "Sin mora", active: true, amounts: flat("L 400.00") },
  { id: "t6", name: "Excursión escolar", frequency: "Única", dueDay: "30 de septiembre", lateFee: "Sin mora", active: false, amounts: flat("L 300.00") },
];

export const scholarships: Scholarship[] = [
  { id: "b1", name: "Beca de excelencia académica", type: "Porcentaje", value: "25 %", concepts: ["Mensualidad"], validFrom: "12/01/2026", validTo: "27/11/2026", active: true },
  { id: "b2", name: "Descuento por hermanos", type: "Porcentaje", value: "10 %", concepts: ["Mensualidad", "Matrícula"], validFrom: "12/01/2026", validTo: "27/11/2026", active: true },
  { id: "b3", name: "Apoyo socioeconómico", type: "Monto fijo", value: "L 500.00", concepts: ["Mensualidad"], validFrom: "12/01/2026", validTo: "27/11/2026", active: true },
  { id: "b4", name: "Beca deportiva", type: "Monto fijo", value: "L 300.00", concepts: ["Transporte", "Uniforme"], validFrom: "01/03/2026", validTo: "31/10/2026", active: false },
];

export const studentScholarships: StudentScholarship[] = [
  { studentId: "s4", scholarshipId: "b2", since: "15/01/2026" },
  { studentId: "s3", scholarshipId: "b1", since: "15/01/2026" },
];
