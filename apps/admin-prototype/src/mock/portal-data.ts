// Guardian portal mock data. The signed-in guardian is fictitious; balances come from the student records.
export const PORTAL_GUARDIAN_ID = "g1";

export type ReportStatus = "Pendiente de revisión" | "Aprobado" | "Rechazado";
export interface PaymentReport {
  id: string; studentId: string; student: string; reference: string; amount: string; date: string; attachment: string; status: ReportStatus; sentOn: string;
}
export const paymentReports: PaymentReport[] = [
  { id: "rp1", studentId: "s2", student: "Mateo Andrés Rápalo Soto", reference: "TRF-90125", amount: "L 2,350.00", date: "06/11/2026", attachment: "comprobante-transferencia.jpg", status: "Pendiente de revisión", sentOn: "06/11/2026 20:15" },
  { id: "rp2", studentId: "s1", student: "Camila Fernanda Rápalo Soto", reference: "TRF-88021", amount: "L 2,350.00", date: "02/10/2026", attachment: "", status: "Aprobado", sentOn: "02/10/2026 18:40" },
  { id: "rp3", studentId: "s2", student: "Mateo Andrés Rápalo Soto", reference: "DEP-5530", amount: "L 1,000.00", date: "15/09/2026", attachment: "deposito.png", status: "Rechazado", sentOn: "15/09/2026 09:02" },
];
