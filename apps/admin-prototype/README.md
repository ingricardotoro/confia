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

## Módulos pendientes (anotados el 2026-11-07, para otro momento)

1. **Cobranza:** morosos, promesas de pago y recordatorios.
2. **Reportes:** ingresos, cartera y cierres de caja.
3. **Bitácora de auditoría.**
4. **Portal de encargados:** la vista del padre o la madre, con el estado de cuenta y los pagos de sus hijos.

## Simplificaciones conocidas

- Los permisos por rol no se aplican: todos los usuarios ven todo.
- No hay estado de cuenta imprimible ni detalle de factura.
- Un pago parcial o una beca no recalculan saldos: el saldo lo calcula el servidor en el sistema real.
- Grados, tarifas y becas no tienen página de detalle, solo listado y formulario.
