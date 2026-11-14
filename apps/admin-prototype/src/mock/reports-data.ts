// Report mock data. Every total, count and percentage is hand-written text; the bars use the written
// percentage as a CSS width. Nothing is added, divided or rounded in the browser.
export interface IncomeRow { label: string; enrollment: string; tuition: string; other: string; total: string; pct: string }
export interface ConceptRow { concept: string; total: string; pct: string }
export interface IncomeSet { key: string; label: string; total: string; payments: string; rows: IncomeRow[]; concepts: ConceptRow[] }

export const incomeSets: IncomeSet[] = [
  {
    key: "year", label: "Año lectivo 2026 (enero a noviembre)", total: "L 1,184,350.00", payments: "1,412 pagos",
    rows: [
      { label: "Enero", enrollment: "L 168,000.00", tuition: "L 0.00", other: "L 24,500.00", total: "L 192,500.00", pct: "100%" },
      { label: "Febrero", enrollment: "L 22,500.00", tuition: "L 94,350.00", other: "L 6,200.00", total: "L 123,050.00", pct: "64%" },
      { label: "Marzo", enrollment: "L 0.00", tuition: "L 101,200.00", other: "L 4,800.00", total: "L 106,000.00", pct: "55%" },
      { label: "Abril", enrollment: "L 0.00", tuition: "L 98,750.00", other: "L 5,100.00", total: "L 103,850.00", pct: "54%" },
      { label: "Mayo", enrollment: "L 0.00", tuition: "L 99,400.00", other: "L 7,300.00", total: "L 106,700.00", pct: "55%" },
      { label: "Junio", enrollment: "L 0.00", tuition: "L 97,150.00", other: "L 6,900.00", total: "L 104,050.00", pct: "54%" },
      { label: "Julio", enrollment: "L 0.00", tuition: "L 95,600.00", other: "L 5,400.00", total: "L 101,000.00", pct: "52%" },
      { label: "Agosto", enrollment: "L 0.00", tuition: "L 93,900.00", other: "L 9,150.00", total: "L 103,050.00", pct: "54%" },
      { label: "Septiembre", enrollment: "L 0.00", tuition: "L 96,300.00", other: "L 5,600.00", total: "L 101,900.00", pct: "53%" },
      { label: "Octubre", enrollment: "L 0.00", tuition: "L 99,100.00", other: "L 8,350.00", total: "L 107,450.00", pct: "56%" },
      { label: "Noviembre (a la fecha)", enrollment: "L 0.00", tuition: "L 29,200.00", other: "L 3,250.00", total: "L 32,450.00", pct: "17%" },
    ],
    concepts: [{ concept: "Mensualidades", total: "L 904,950.00", pct: "76%" }, { concept: "Matrícula", total: "L 190,500.00", pct: "16%" }, { concept: "Otros conceptos", total: "L 88,900.00", pct: "8%" }],
  },
  {
    key: "quarter", label: "Último trimestre (septiembre a noviembre)", total: "L 241,800.00", payments: "287 pagos",
    rows: [
      { label: "Septiembre", enrollment: "L 0.00", tuition: "L 96,300.00", other: "L 5,600.00", total: "L 101,900.00", pct: "95%" },
      { label: "Octubre", enrollment: "L 0.00", tuition: "L 99,100.00", other: "L 8,350.00", total: "L 107,450.00", pct: "100%" },
      { label: "Noviembre (a la fecha)", enrollment: "L 0.00", tuition: "L 29,200.00", other: "L 3,250.00", total: "L 32,450.00", pct: "30%" },
    ],
    concepts: [{ concept: "Mensualidades", total: "L 224,600.00", pct: "93%" }, { concept: "Matrícula", total: "L 0.00", pct: "0%" }, { concept: "Otros conceptos", total: "L 17,200.00", pct: "7%" }],
  },
  {
    key: "october", label: "Octubre 2026", total: "L 107,450.00", payments: "131 pagos",
    rows: [
      { label: "Semana 1", enrollment: "L 0.00", tuition: "L 31,400.00", other: "L 2,100.00", total: "L 33,500.00", pct: "100%" },
      { label: "Semana 2", enrollment: "L 0.00", tuition: "L 26,800.00", other: "L 1,950.00", total: "L 28,750.00", pct: "86%" },
      { label: "Semana 3", enrollment: "L 0.00", tuition: "L 22,300.00", other: "L 2,400.00", total: "L 24,700.00", pct: "74%" },
      { label: "Semana 4", enrollment: "L 0.00", tuition: "L 18,600.00", other: "L 1,900.00", total: "L 20,500.00", pct: "61%" },
    ],
    concepts: [{ concept: "Mensualidades", total: "L 99,100.00", pct: "92%" }, { concept: "Matrícula", total: "L 0.00", pct: "0%" }, { concept: "Otros conceptos", total: "L 8,350.00", pct: "8%" }],
  },
  { key: "december", label: "Diciembre 2025 (receso escolar)", total: "L 0.00", payments: "0 pagos", rows: [], concepts: [] },
];

export interface PortfolioBucket { bucket: string; students: string; amount: string; pct: string }
export interface PortfolioGrade { grade: string; students: string; current: string; overdue: string; total: string; pct: string }
export const portfolioTotal = { total: "L 61,550.00", overdue: "L 48,300.00", current: "L 13,250.00", students: "9 estudiantes con saldo" };
export const portfolioBuckets: PortfolioBucket[] = [
  { bucket: "Al corriente (aún sin vencer)", students: "3", amount: "L 13,250.00", pct: "22%" },
  { bucket: "1 a 30 días", students: "4", amount: "L 10,300.00", pct: "17%" },
  { bucket: "31 a 60 días", students: "2", amount: "L 10,300.00", pct: "17%" },
  { bucket: "61 a 90 días", students: "1", amount: "L 8,400.00", pct: "14%" },
  { bucket: "Más de 90 días", students: "1", amount: "L 7,050.00", pct: "12%" },
];
export const portfolioGrades: PortfolioGrade[] = [
  { grade: "Preparatoria", students: "1", current: "L 0.00", overdue: "L 2,350.00", total: "L 2,350.00", pct: "4%" },
  { grade: "1.º grado", students: "1", current: "L 0.00", overdue: "L 7,050.00", total: "L 7,050.00", pct: "11%" },
  { grade: "2.º grado", students: "1", current: "L 0.00", overdue: "L 2,350.00", total: "L 2,350.00", pct: "4%" },
  { grade: "3.º grado", students: "1", current: "L 0.00", overdue: "L 4,700.00", total: "L 4,700.00", pct: "8%" },
  { grade: "4.º grado", students: "1", current: "L 2,800.00", overdue: "L 5,600.00", total: "L 8,400.00", pct: "14%" },
  { grade: "5.º grado", students: "1", current: "L 0.00", overdue: "L 2,800.00", total: "L 2,800.00", pct: "5%" },
  { grade: "6.º grado", students: "2", current: "L 0.00", overdue: "L 8,400.00", total: "L 8,400.00", pct: "14%" },
  { grade: "7.º grado", students: "0", current: "L 0.00", overdue: "L 0.00", total: "L 0.00", pct: "0%" },
];

export interface Closing {
  id: string; cashier: string; opened: string; closed: string; opening: string; collected: string; cash: string; transfers: string; cards: string;
  expected: string; counted: string; difference: string; note: string; payments: string;
}
export const closings: Closing[] = [
  { id: "z1", cashier: "Oscar Valladares", opened: "06/11/2026 07:45", closed: "06/11/2026 17:05", opening: "L 1,000.00", collected: "L 4,800.00", cash: "L 0.00", transfers: "L 2,350.00", cards: "L 2,450.00", expected: "L 1,000.00", counted: "L 1,000.00", difference: "L 0.00", note: "Sin novedades.", payments: "2 pagos" },
  { id: "z2", cashier: "Karla Mejía", opened: "05/11/2026 07:28", closed: "05/11/2026 16:55", opening: "L 1,000.00", collected: "L 7,950.00", cash: "L 5,600.00", transfers: "L 2,350.00", cards: "L 0.00", expected: "L 6,600.00", counted: "L 6,550.00", difference: "-L 50.00", note: "Faltante de L 50.00 en efectivo. Se revisó el conteo dos veces.", payments: "3 pagos" },
  { id: "z3", cashier: "Karla Mejía", opened: "04/11/2026 07:30", closed: "04/11/2026 17:10", opening: "L 1,000.00", collected: "L 3,150.00", cash: "L 2,350.00", transfers: "L 800.00", cards: "L 0.00", expected: "L 3,350.00", counted: "L 3,350.00", difference: "L 0.00", note: "Sin novedades.", payments: "2 pagos" },
  { id: "z4", cashier: "Oscar Valladares", opened: "03/11/2026 07:50", closed: "03/11/2026 17:00", opening: "L 1,000.00", collected: "L 5,600.00", cash: "L 2,800.00", transfers: "L 2,800.00", cards: "L 0.00", expected: "L 3,800.00", counted: "L 3,825.00", difference: "L 25.00", note: "Sobrante de L 25.00; no se identificó el pago.", payments: "2 pagos" },
  { id: "z5", cashier: "Karla Mejía", opened: "31/10/2026 07:25", closed: "31/10/2026 16:40", opening: "L 1,000.00", collected: "L 9,400.00", cash: "L 4,700.00", transfers: "L 2,350.00", cards: "L 2,350.00", expected: "L 5,700.00", counted: "L 5,700.00", difference: "L 0.00", note: "Sin novedades.", payments: "4 pagos" },
  { id: "z6", cashier: "Oscar Valladares", opened: "30/10/2026 07:40", closed: "30/10/2026 17:15", opening: "L 1,000.00", collected: "L 2,800.00", cash: "L 2,800.00", transfers: "L 0.00", cards: "L 0.00", expected: "L 3,800.00", counted: "L 3,800.00", difference: "L 0.00", note: "Sin novedades.", payments: "1 pago" },
];
