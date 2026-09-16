# Registro de rotación de secretos

> Bitácora de rotación de credenciales y llaves. Un secreto sin registro de rotación en su período
> aparece en la alerta mensual de higiene de secretos.

> **Este archivo no contiene ningún secreto.** Registra que la rotación ocurrió, quién la hizo y
> cómo se verificó. Los valores viven en el gestor de secretos, nunca aquí.

---

## Inventario de secretos y su cadencia

| Secreto | Dónde vive | Cadencia | Última rotación | Próxima |
|---|---|---|---|---|
| Contraseña del rol de aplicación de PostgreSQL | Gestor de secretos | Semestral | | |
| Contraseña del rol del portal de PostgreSQL | Gestor de secretos | Semestral | | |
| Contraseña del rol de solo lectura para MCP | Gestor de secretos | Semestral | | |
| Llave de firma de tokens del personal | Gestor de secretos | Trimestral | | |
| Llave de firma de tokens de encargados | Gestor de secretos | Trimestral | | |
| Llave de cifrado de datos personales | Gestor de secretos, con envoltura | Anual | | |
| Llave de cifrado de respaldos | Fuera del servidor, custodia separada | Anual | | |
| Contraseña de Redis | Gestor de secretos | Semestral | | |
| Credenciales del almacenamiento de archivos | Gestor de secretos | Semestral | | |
| Clave de API de la pasarela de pago | Gestor de secretos | Anual o ante incidente | | |
| Secreto de firma de webhook de la pasarela | Gestor de secretos | Anual o ante incidente | | |
| Credenciales del proveedor de correo | Gestor de secretos | Anual | | |
| Llaves SSH de despliegue | Agente SSH | Anual | | |
| Token del registro de imágenes | Secretos del repositorio | Anual | | |
| Frase de paso del cifrado de disco | Custodia física | Ante cambio de personal con acceso | | |

---

## Reglas de rotación

1. **La llave de firma de tokens se rota con superposición.** Se acepta la anterior durante el
   tiempo de vida del token de refresco, para no expulsar a todo el mundo de golpe. Pasado ese
   plazo, se retira.
2. **La llave de cifrado de datos personales nunca se rota sin plan de recifrado.** Rotarla sin
   recifrar deja los datos existentes indescifrables. Es una operación planificada, no un
   procedimiento de rutina.
3. **La llave de respaldos se conserva mientras exista un respaldo cifrado con ella.** Rotarla y
   destruir la anterior convierte todos los respaldos previos en basura cifrada.
4. **Toda rotación se verifica antes de retirar el valor anterior.** Un despliegue con el secreto
   nuevo mal configurado y el anterior ya destruido es una caída sin salida rápida.
5. **Ante cualquier incidente de seguridad, se rota todo**, sin importar la cadencia. Ver
   `docs/runbooks/incidente-de-seguridad.md`.
6. **Ante la salida de una persona con acceso**, se rota todo lo que esa persona pudo ver.

---

## Bitácora

| Fecha | Secreto | Motivo | Actor | Verificación realizada | Valor anterior retirado |
|---|---|---|---|---|---|
| | | | | | |

Motivos válidos: `programada`, `incidente`, `cambio de personal`, `sospecha de exposición`,
`requisito del proveedor`.

La columna de verificación describe **qué se comprobó**, no que se comprobó. Por ejemplo: "inicio de
sesión correcto en preproducción y en producción, y trabajo de segundo plano procesando la cola".

---

## Exposición accidental de un secreto

Si un secreto llega al repositorio, a un registro de aplicación, a una captura de pantalla o a un
canal de mensajería:

1. **Rota de inmediato.** El secreto está comprometido desde el instante en que salió, no desde que
   alguien lo notó.
2. Registra el evento en esta bitácora con motivo de sospecha de exposición.
3. Solo después, limpia el rastro. Limpiar sin rotar no sirve: quien lo copió ya lo tiene.
4. Si llegó al repositorio, evalúa si el historial se replicó. Si se replicó, la limpieza del
   historial es cosmética.
5. Si el secreto daba acceso a datos personales, evalúalo como posible incidente de seguridad.

---

## Alerta mensual de higiene

Un trabajo mensual revisa esta tabla y alerta sobre cualquier secreto cuya próxima rotación esté
vencida o a menos de treinta días. La alerta es accionable: nombra el secreto y su fecha.
