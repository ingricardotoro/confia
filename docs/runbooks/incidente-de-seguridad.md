# Runbook: Incidente de seguridad

- **Severidad:** Crítica
- **Tiempo objetivo de contención:** 1 hora
- **Última prueba de este procedimiento:** pendiente

---

## Síntoma

Cualquiera de estos:

- Accesos exitosos desde direcciones o países inusuales.
- Pico de intentos fallidos de autenticación.
- Consultas o exportaciones masivas fuera de patrón.
- Modificación de datos financieros sin operación de negocio asociada.
- Rotura de la cadena de hash de la bitácora de auditoría.
- Alerta del cortafuegos de aplicación o del proveedor de hosting.
- Un usuario reporta actividad que no realizó.
- Aparición de archivos, procesos o tareas programadas desconocidas en el servidor.

## Impacto si no se atiende

Exposición de datos personales de menores de edad y de sus responsables, manipulación de registros
financieros, y obligación legal de notificar una brecha. La reputación de la institución ante las
familias es el activo más difícil de recuperar.

---

## Clasificación de severidad

Clasifica antes de actuar. Determina a quién se avisa y con qué urgencia.

| Nivel | Definición | Ejemplos | Notificación |
|---|---|---|---|
| **S1 Crítica** | Acceso confirmado a datos personales o financieros, o control del servidor | Fuga de base de datos, acceso administrativo no autorizado, ransomware | Dirección de inmediato. Evaluar notificación de brecha |
| **S2 Alta** | Compromiso de una cuenta, o vulnerabilidad explotable expuesta | Cuenta de personal comprometida, credencial filtrada | Dirección el mismo día |
| **S3 Media** | Intento fallido, o vulnerabilidad sin exposición confirmada | Ataque de fuerza bruta bloqueado, dependencia vulnerable no explotada | Registro y corrección planificada |
| **S4 Baja** | Ruido de fondo | Escaneo automatizado bloqueado por el cortafuegos | Solo registro |

---

## Regla que nunca se rompe

> **No reinicies ni apagues el servidor antes de capturar la evidencia.**
>
> Reiniciar destruye la memoria, los procesos en ejecución y las conexiones abiertas, que suelen ser
> la única prueba de qué hizo el atacante y por dónde entró. La contención se hace **aislando la
> red**, no apagando la máquina.
>
> El impulso de apagar es fuerte y es el error más común en el primer incidente de una organización.

---

## Contención, primera hora

Ejecuta en este orden.

### 1. Aislar sin apagar

```bash
# Cortar el trafico entrante manteniendo el acceso administrativo
sudo ufw default deny incoming
sudo ufw allow from <IP_ADMIN_CONFIABLE> to any port 22
sudo ufw reload
```

Si el compromiso es del servidor de aplicación, detén los contenedores de aplicación pero **deja
corriendo la base de datos y el sistema operativo**:

```bash
docker compose -f infra/docker/docker-compose.prod.yml stop api-admin api-portal worker
```

### 2. Capturar evidencia antes de tocar nada más

```bash
mkdir -p /var/evidence/$(date +%Y%m%d_%H%M)
cd /var/evidence/$(date +%Y%m%d_%H%M)

ps auxf                     > procesos.txt
ss -tunap                   > conexiones.txt
who; last -50               > sesiones.txt
docker ps -a                > contenedores.txt
docker compose -f /opt/confia/infra/docker/docker-compose.prod.yml logs --no-color > app.log
sudo journalctl --since "48 hours ago" --no-pager > sistema.log
sudo cp /var/log/auth.log auth.log
sudo cp /var/log/nginx/access.log nginx-access.log
crontab -l                  > cron-usuario.txt 2>/dev/null
sudo ls -la /etc/cron.d/    > cron-sistema.txt

sha256sum * > HASHES.txt
```

En AWS, complementa con el historial de sesiones de Session Manager y con AWS CloudTrail (llamadas
a la API de AWS, incluidas las de RDS y S3), que no dependen del host potencialmente comprometido.

Copia el directorio de evidencia **fuera del servidor comprometido** de inmediato.

### 3. Revocar accesos

```sql
-- Invalidar todas las sesiones activas del personal
UPDATE refresh_tokens SET revoked_at = now() WHERE revoked_at IS NULL;
```

Rota, en este orden:

1. Credenciales de base de datos
2. Llaves de firma de tokens, lo que invalida todo token emitido
3. Claves de API de la pasarela de pago y del proveedor de correo
4. Credenciales y sesiones de AWS: revoca las sesiones activas de Session Manager, rota el rol
   de despliegue asumido por OIDC y, fuera de AWS, las llaves SSH del servidor
5. Tokens del repositorio y del registro de imágenes

**No rotes la llave de descifrado de respaldos** hasta confirmar que los respaldos están íntegros y
que tienes acceso a ellos con la llave actual.

### 4. Avisar

Notifica a la dirección de la institución. Con datos, no con hipótesis: qué se detectó, a qué hora,
qué se hizo para contener, y qué todavía no se sabe.

---

## Diagnóstico

### Verificar la integridad de la bitácora de auditoría

Es la primera pregunta, porque determina si se puede confiar en el resto de la investigación.

```sql
SELECT count(*) AS eslabones_rotos
  FROM audit_log a
  JOIN audit_log p ON p.id = a.previous_id
 WHERE a.previous_hash <> p.record_hash;
```

Un resultado distinto de cero significa manipulación de la bitácora, lo que eleva el incidente a S1
de forma automática.

### Reconstruir la actividad del atacante

```sql
-- Accesos exitosos desde direcciones no habituales
SELECT actor_id, ip_address, user_agent, min(occurred_at), max(occurred_at), count(*)
  FROM audit_log
 WHERE action = 'AUTH_SUCCESS'
   AND occurred_at >= now() - interval '30 days'
 GROUP BY 1,2,3
 ORDER BY min(occurred_at) DESC;

-- Exportaciones y lecturas masivas
SELECT actor_id, action, entity_type, count(*), min(occurred_at), max(occurred_at)
  FROM audit_log
 WHERE action IN ('EXPORT','BULK_READ','REPORT_GENERATE')
   AND occurred_at >= now() - interval '30 days'
 GROUP BY 1,2,3
HAVING count(*) > 50
 ORDER BY count(*) DESC;

-- Cambios financieros o de permisos
SELECT * FROM audit_log
 WHERE action IN ('LEDGER_ADJUST','PAYMENT_REVERSE','INVOICE_VOID','ROLE_CHANGE','PERMISSION_GRANT')
   AND occurred_at >= now() - interval '30 days'
 ORDER BY occurred_at DESC;
```

### Determinar qué datos se expusieron

Es la pregunta que decide si hay obligación de notificar. Cuantifica:

- Cuántos estudiantes, con qué categorías de dato
- Cuántos encargados
- Si hubo acceso a documentos de identidad, contactos o datos financieros
- Si hay datos de menores involucrados, que es el caso por defecto

---

## Erradicación y recuperación

1. **Identifica el vector de entrada.** No sirve limpiar sin saber por dónde entró: vuelve a entrar.
2. **Corrige la vulnerabilidad** antes de restaurar el servicio.
3. **Reconstruye desde una base limpia.** Si el servidor fue comprometido, no se limpia: se
   reaprovisiona desde la infraestructura como código, con imágenes reconstruidas desde el
   repositorio verificado. Un servidor comprometido nunca vuelve a ser confiable.
4. **Restaura datos desde un respaldo anterior al compromiso** si hubo manipulación. Ver
   `restauracion-de-respaldo.md`.
5. **Verifica la integridad financiera** antes de reabrir. Ver `descuadre-de-libro-mayor.md`.
6. **Fuerza el cambio de contraseña** de todo el personal, con MFA obligatoria reconfigurada.
7. **Reabre por etapas**: primero el panel administrativo con acceso restringido, verifica, y solo
   después el portal público.
8. **Monitorea de forma intensiva** durante al menos dos semanas.

---

## Notificación de brecha

Si se confirma acceso no autorizado a datos personales, hay obligación de notificar.

**Plazo de referencia:** 72 horas desde la detección para la autoridad, y sin demora indebida para
las personas afectadas cuando el riesgo para sus derechos es alto. El tratamiento de datos de
menores eleva ese riesgo por defecto.

**La obligación legal concreta en Honduras debe confirmarse con asesoría legal.** Este runbook
describe el procedimiento, no el dictamen jurídico.

Contenido mínimo de la notificación:

- Naturaleza de la brecha y categorías de datos afectadas
- Número aproximado de personas afectadas
- Consecuencias probables
- Medidas adoptadas y propuestas
- Punto de contacto para consultas

Ver `docs/08-datos-privacidad-y-retencion.md`.

---

## Verificación

- [ ] Vector de entrada identificado y cerrado
- [ ] Evidencia capturada, con hashes, y almacenada fuera del servidor
- [ ] Todas las credenciales rotadas
- [ ] Sesiones invalidadas y contraseñas del personal renovadas
- [ ] Servidor reaprovisionado desde base limpia si hubo compromiso
- [ ] Cadena de auditoría verificada
- [ ] Integridad financiera verificada
- [ ] Alcance de datos expuestos cuantificado
- [ ] Decisión de notificación tomada, con asesoría legal
- [ ] Monitoreo intensivo activo

---

## Prevención

Los controles preventivos están en `docs/03-seguridad.md`. Los más relevantes para este runbook:

- MFA obligatoria para todo rol con escritura financiera
- Bitácora de auditoría encadenada por hash, sin permisos de borrado ni actualización
- Copia de la bitácora a almacenamiento de solo escritura
- Base de datos sin puerto público
- Cortafuegos de aplicación en el borde
- Escaneo de dependencias, de imágenes y de secretos en integración continua
- Alertas de acceso anómalo y de exportación masiva
- Prueba de penetración antes de producción y anual
- **Simulacro de este runbook en mesa, cada seis meses**

---

## Escalamiento

| Situación | A quién | Cuándo |
|---|---|---|
| Cualquier incidente S1 o S2 | Dirección de la institución | Inmediato |
| Datos personales expuestos | Asesoría legal | Antes de las 24 horas |
| Datos financieros manipulados | Contador | Inmediato |
| Compromiso del servidor | Proveedor de hosting | Inmediato |
| Datos de pago involucrados | Pasarela y banco | Inmediato |
| Posible delito | Dirección decide si denuncia | Tras la contención |

---

## Registro del incidente

Documento formal con: cronología detallada, vector de entrada, alcance de datos, acciones de
contención con su hora, credenciales rotadas, decisión de notificación con su fundamento, causa
raíz, y plan de mejoras con responsables y fechas.

Este documento se conserva de forma permanente y es el insumo principal de la revisión posterior al
incidente.
