# Contactos de incidente

> **Plantilla.** Este archivo se llena con los datos reales de la institución antes de salir a
> producción, y se mantiene actualizado.

> **Advertencia.** Si el repositorio llegara a ser público, este archivo se retira de él y se
> mantiene en el gestor de secretos o en un documento interno de la institución. Contiene datos de
> contacto de personas.

> **Mantén siempre una copia impresa.** Un incidente puede dejar sin acceso al repositorio, al
> correo corporativo o a la red. Una lista de contactos que solo existe dentro del sistema caído no
> sirve de nada. Esta es la razón por la que este archivo existe como documento y no solo como una
> sección de otro.

---

## Contactos internos

| Rol | Nombre | Teléfono | Correo | Horario | Suplente |
|---|---|---|---|---|---|
| Responsable técnico del sistema | | | | | |
| Dirección de la institución | | | | | |
| Contador de la institución | | | | | |
| Responsable administrativo | | | | | |
| Departamento de sistemas de la institución | | | | | |

**Nota sobre la suplencia.** Hoy no existe suplente del responsable técnico. Es el riesgo de mayor
exposición del proyecto y está registrado como tal en `docs/11-riesgos.md`. La transferencia
tecnológica al departamento de sistemas es la mitigación planificada.

---

## Proveedores

| Servicio | Proveedor | Soporte | Contrato o cuenta | Tiempo de respuesta comprometido |
|---|---|---|---|---|
| Alojamiento del servidor | | | | |
| Dominio y DNS | | | | |
| Cortafuegos de aplicación y TLS | | | | |
| Pasarela de pago | | | | |
| Banco de la institución | | | | |
| Proveedor de correo transaccional | | | | |
| Mensajería o SMS | | | | |

---

## Autoridades y terceros

| Entidad | Cuándo se contacta | Contacto | Plazo |
|---|---|---|---|
| Autoridad tributaria | Problemas con rangos autorizados o documentos fiscales | | Según trámite |
| Autoridad de protección de datos | Brecha de datos personales confirmada | | Referencia: 72 horas desde la detección |
| Asesoría legal | Antes de cualquier notificación de brecha | | |
| Seguro de responsabilidad, si existe | Incidente con impacto financiero | | Según póliza |

**El plazo de notificación de brecha y la autoridad competente en Honduras deben confirmarse con
asesoría legal.** El valor de referencia de 72 horas proviene del estándar internacional adoptado
como marco, no de una obligación local verificada. Ver `docs/08-datos-privacidad-y-retencion.md`.

---

## Orden de escalamiento

| Severidad | Primero | Después | Plazo |
|---|---|---|---|
| S1 Crítica | Responsable técnico | Dirección, de inmediato | Contención en 1 hora |
| S2 Alta | Responsable técnico | Dirección, el mismo día | Contención en 4 horas |
| S3 Media | Responsable técnico | Informe semanal | Corrección planificada |
| S4 Baja | Registro | | |

Para incidentes con implicación fiscal, el contador entra en el escalamiento sin importar la
severidad técnica: un problema menor de software puede ser un problema mayor ante la autoridad
tributaria.

---

## Revisión

Esta lista se revisa **cada seis meses** y ante cualquier cambio de personal o de proveedor. Una
lista de contactos desactualizada descubierta durante un incidente cuesta horas que no se tienen.

| Fecha de revisión | Revisado por | Cambios |
|---|---|---|
| | | |
