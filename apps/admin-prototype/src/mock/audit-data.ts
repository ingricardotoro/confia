// Fictitious audit events. Everything is read-only text; nothing here refers to real people.
export type AuditResult = "Exitoso" | "Fallido" | "Denegado";
export interface AuditEvent {
  id: string; date: string; time: string; actor: string; role: string; action: string; entity: string; entityRef: string;
  result: AuditResult; ip: string; detail: [string, string][];
}

export const AUDIT_ACTIONS = ["Inicio de sesión", "Inicio de sesión fallido", "Pago registrado", "Pago anulado", "Cambio de rol", "Permiso denegado", "Concepto de cobro editado", "Datos de estudiante consultados"];
export const AUDIT_ENTITIES = ["Sesión", "Pago", "Usuario del personal", "Concepto de cobro", "Estudiante", "Recurso protegido"];
export const AUDIT_ACTORS = ["Marlene Cáceres", "Iván Portillo", "Karla Mejía", "Oscar Valladares", "Teresa Bonilla", "Néstor Reyes", "Alma Sierra", "Desconocido"];
export const AUDIT_DATES = ["07/11/2026", "06/11/2026", "05/11/2026", "04/11/2026"];

const ok: AuditResult = "Exitoso";
export const auditEvents: AuditEvent[] = [
  { id: "ev16", date: "07/11/2026", time: "09:14:22", actor: "Karla Mejía", role: "Cajero", action: "Pago registrado", entity: "Pago", entityRef: "REC-000231", result: ok, ip: "190.92.14.21",
    detail: [["Estudiante", "Mateo Andrés Rápalo Soto"], ["Monto", "L 2,350.00"], ["Método", "Efectivo"], ["Clave de idempotencia", "k-7f21…c9a0"]] },
  { id: "ev15", date: "07/11/2026", time: "08:47:03", actor: "Karla Mejía", role: "Cajero", action: "Pago registrado", entity: "Pago", entityRef: "REC-000230", result: ok, ip: "190.92.14.21",
    detail: [["Estudiante", "Ximena Alejandra Bardales Rivera"], ["Monto", "L 2,800.00"], ["Método", "Transferencia bancaria"], ["Referencia", "TRF-88412"]] },
  { id: "ev14", date: "07/11/2026", time: "08:12:40", actor: "Marlene Cáceres", role: "Administrador", action: "Inicio de sesión", entity: "Sesión", entityRef: "SES-4471", result: ok, ip: "190.92.14.10",
    detail: [["Método de acceso", "Contraseña y segundo factor"], ["Dispositivo", "Navegador de escritorio"], ["Sesión", "SES-4471"]] },
  { id: "ev13", date: "07/11/2026", time: "07:58:11", actor: "Desconocido", role: "Sin rol", action: "Inicio de sesión fallido", entity: "Sesión", entityRef: "usuario: o.valladares", result: "Fallido", ip: "181.115.60.9",
    detail: [["Motivo", "Contraseña incorrecta"], ["Intentos consecutivos", "2"], ["Cuenta bloqueada", "No"]] },
  { id: "ev12", date: "07/11/2026", time: "07:55:30", actor: "Karla Mejía", role: "Cajero", action: "Inicio de sesión", entity: "Sesión", entityRef: "SES-4470", result: ok, ip: "190.92.14.21",
    detail: [["Método de acceso", "Contraseña y segundo factor"], ["Dispositivo", "Navegador de escritorio"], ["Sesión", "SES-4470"]] },
  { id: "ev11", date: "06/11/2026", time: "17:40:09", actor: "Iván Portillo", role: "Super Admin", action: "Cambio de rol", entity: "Usuario del personal", entityRef: "Alma Sierra", result: ok, ip: "190.92.14.5",
    detail: [["Rol anterior", "Cajero"], ["Rol nuevo", "Coordinador Académico"], ["Motivo indicado", "Cambio de funciones"], ["Aprobado por", "Iván Portillo"]] },
  { id: "ev10", date: "06/11/2026", time: "16:20:48", actor: "Oscar Valladares", role: "Cajero", action: "Pago registrado", entity: "Pago", entityRef: "REC-000229", result: ok, ip: "190.92.14.33",
    detail: [["Estudiante", "Daniela Sofía Lagos Pineda"], ["Monto", "L 2,800.00"], ["Método", "Tarjeta de débito"], ["Autorización", "AUT-5521"]] },
  { id: "ev9", date: "06/11/2026", time: "15:02:19", actor: "Oscar Valladares", role: "Cajero", action: "Permiso denegado", entity: "Concepto de cobro", entityRef: "Mensualidad", result: "Denegado", ip: "190.92.14.33",
    detail: [["Acción intentada", "Editar un concepto de cobro"], ["Permiso requerido", "fees.edit"], ["Rol del usuario", "Cajero"], ["Resultado", "El servidor rechazó la solicitud (403)"]] },
  { id: "ev8", date: "06/11/2026", time: "11:30:55", actor: "Teresa Bonilla", role: "Contabilidad", action: "Concepto de cobro editado", entity: "Concepto de cobro", entityRef: "Transporte escolar", result: ok, ip: "190.92.14.18",
    detail: [["Campo", "Monto por grado: 4.º grado"], ["Valor anterior", "L 650.00"], ["Valor nuevo", "L 700.00"], ["Vigente desde", "01/12/2026"]] },
  { id: "ev7", date: "06/11/2026", time: "10:05:37", actor: "Néstor Reyes", role: "Auditor", action: "Datos de estudiante consultados", entity: "Estudiante", entityRef: "E-2026-0106", result: ok, ip: "190.92.14.40",
    detail: [["Pantalla", "Ficha del estudiante"], ["Datos vistos", "Datos generales y encargados"], ["Documento de identidad", "Enmascarado"], ["Motivo", "Revisión de auditoría"]] },
  { id: "ev6", date: "05/11/2026", time: "15:44:02", actor: "Marlene Cáceres", role: "Administrador", action: "Pago anulado", entity: "Pago", entityRef: "REC-000224", result: ok, ip: "190.92.14.10",
    detail: [["Estudiante", "Camila Fernanda Rápalo Soto"], ["Monto", "L 2,350.00"], ["Motivo", "Pago duplicado"], ["Asiento de reverso", "REV-000019"], ["Segunda autorización", "Iván Portillo"]] },
  { id: "ev5", date: "05/11/2026", time: "13:10:26", actor: "Desconocido", role: "Sin rol", action: "Inicio de sesión fallido", entity: "Sesión", entityRef: "usuario: admin", result: "Fallido", ip: "45.33.120.77",
    detail: [["Motivo", "Usuario inexistente"], ["Intentos consecutivos", "5"], ["Dirección bloqueada", "Sí, por 15 minutos"]] },
  { id: "ev4", date: "05/11/2026", time: "09:30:14", actor: "Alma Sierra", role: "Coordinador Académico", action: "Permiso denegado", entity: "Recurso protegido", entityRef: "/reportes/cierres", result: "Denegado", ip: "190.92.14.27",
    detail: [["Acción intentada", "Abrir el reporte de cierres de caja"], ["Permiso requerido", "reports.cash"], ["Rol del usuario", "Coordinador Académico"], ["Resultado", "El servidor rechazó la solicitud (403)"]] },
  { id: "ev3", date: "04/11/2026", time: "17:10:51", actor: "Karla Mejía", role: "Cajero", action: "Inicio de sesión", entity: "Sesión", entityRef: "SES-4462", result: ok, ip: "190.92.14.21",
    detail: [["Método de acceso", "Contraseña y segundo factor"], ["Dispositivo", "Navegador de escritorio"], ["Sesión", "SES-4462"]] },
  { id: "ev2", date: "04/11/2026", time: "10:22:08", actor: "Marlene Cáceres", role: "Administrador", action: "Datos de estudiante consultados", entity: "Estudiante", entityRef: "E-2026-0104", result: ok, ip: "190.92.14.10",
    detail: [["Pantalla", "Estado de cuenta del estudiante"], ["Datos vistos", "Cargos y pagos"], ["Documento de identidad", "Enmascarado"]] },
  { id: "ev1", date: "04/11/2026", time: "08:01:33", actor: "Iván Portillo", role: "Super Admin", action: "Cambio de rol", entity: "Usuario del personal", entityRef: "Néstor Reyes", result: ok, ip: "190.92.14.5",
    detail: [["Rol anterior", "Contabilidad"], ["Rol nuevo", "Auditor"], ["Motivo indicado", "Nueva asignación"], ["Aprobado por", "Iván Portillo"]] },
];
