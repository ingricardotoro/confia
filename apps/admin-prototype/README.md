# Prototipo navegable del panel administrativo

Prototipo de pantallas de CONFIA para validar flujos con el propietario. Vive en la rama `prototype/admin-web` y **nunca se fusiona a `main`**.

- Datos ficticios en memoria, sin backend.
- Sin pruebas ni especificaciones SDD.
- Los importes son texto ya formateado: el navegador nunca calcula dinero.

Cuando una pantalla queda validada, se especifica y se construye en `apps/admin-web` con el ciclo SDD completo, usando este prototipo como referencia.

## Cómo ejecutarlo (PowerShell)

```powershell
cd C:\dev\confia-prototype
pnpm install
pnpm --filter @confia/contracts generate   # solo la primera vez
pnpm --filter @confia/admin-prototype dev
# abrir http://localhost:5174/ (cualquier dato entra en el inicio de sesión)
```

## Módulos hechos

| Módulo | Pantallas |
|---|---|
| Acceso | Inicio de sesión ficticio y cierre de sesión |
| Resumen | Indicadores y actividad reciente |
| Organizaciones | Listado, detalle, alta y edición |
| Usuarios del personal | Seis roles, activación y estado de MFA |
| Estudiantes | Filtros por grado, sección y saldo; pestañas de datos, encargados, estado de cuenta, pagos y becas |
| Encargados | Con sus estudiantes vinculados |
| Pagos | Registro en tres pasos, listado, recibo y anulación |
| Cargos, caja y facturación | Cargos pendientes, apertura y cierre de caja, facturas con CAI y correlativo de prueba |
| Configuración académica | Períodos, grados y secciones, tarifas y becas |
| Cobranza | Morosos (filtros por grado y tramo, detalle con cargos e historial de contacto), promesas de pago (alta, edición, marcar cumplida o incumplida) y recordatorios |
| Reportes | Ingresos (rangos preparados y barras), cartera por tramo y por grado, cierres de caja con detalle; «Exportar» es simulado |
| Auditoría | Bitácora de solo lectura con filtros y panel de detalle |
| Portal de encargados | Entrada desde el inicio de sesión o el pie del menú (`#/portal`): hijos y saldos, estado de cuenta, pagos y recibos, reporte de pago, preferencias |

## Módulos pendientes

Ninguno de los cuatro módulos anotados el 2026-11-07 queda pendiente. Cualquier módulo nuevo se anota aquí.

## Simplificaciones conocidas

- Los morosos, los reportes y la bitácora son datos escritos a mano: no se recalculan al registrar pagos ni se alinean al céntimo con el módulo de estudiantes.
- Los recordatorios no se envían; el número de destinatarios sale de una tabla escrita a mano.
- Las barras de los reportes usan el porcentaje escrito como ancho CSS; los rangos de fechas solo cambian entre conjuntos preparados.
- El portal de encargados no tiene autenticación propia: muestra siempre a la encargada ficticia Patricia Soto de Rápalo. «Cerrar sesión» vuelve al inicio de sesión del personal.
- Los reportes de pago del portal no llegan al módulo de pagos del personal.

- Los permisos por rol no se aplican: todos los usuarios ven todo.
- No hay estado de cuenta imprimible ni detalle de factura.
- Un pago parcial o una beca no recalculan saldos: el saldo lo calcula el servidor en el sistema real.
- Grados, tarifas y becas no tienen página de detalle, solo listado y formulario.
