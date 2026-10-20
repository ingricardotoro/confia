# Informe de archivo: Núcleo de tokens, familias de refresco y capa web

- **Cambio:** `session-tokens-and-web-layer` (F0, cambio 7, parte 4b, S1, primero de tres)
- **Archivado:** 2026-10-19
- **Modo de almacenamiento:** hybrid (rutas de archivo para los artefactos; Engram para este informe)
- **Base del cambio:** `main` en `0d16c40`; el branch `change/session-tokens-and-web-layer-archive` se creó desde ese commit
- **Estado de cierre:** completo en implementación. 15 de 15 tareas marcadas `[x]`, sin casillas abiertas. Verificación con advertencias y sin hallazgos críticos ni bloqueantes. Las advertencias 1 a 4 quedan tratadas en este archivo o transferidas con dueño, según la sección correspondiente.

## Artefactos

| Artefacto | Ubicación | Estado |
|-----------|-----------|--------|
| proposal.md | `openspec/changes/archive/2026-10-19-session-tokens-and-web-layer/proposal.md` | presente |
| design.md | `openspec/changes/archive/2026-10-19-session-tokens-and-web-layer/design.md` | presente |
| exploration.md | `openspec/changes/archive/2026-10-19-session-tokens-and-web-layer/exploration.md` | presente |
| specs/identity/spec.md (delta) | `openspec/changes/archive/2026-10-19-session-tokens-and-web-layer/specs/identity/spec.md` | presente |
| specs/organization/spec.md (delta) | `openspec/changes/archive/2026-10-19-session-tokens-and-web-layer/specs/organization/spec.md` | presente |
| specs/web-edge/spec.md (delta) | `openspec/changes/archive/2026-10-19-session-tokens-and-web-layer/specs/web-edge/spec.md` | presente |
| specs/build-integrity/spec.md (delta) | `openspec/changes/archive/2026-10-19-session-tokens-and-web-layer/specs/build-integrity/spec.md` | presente |
| tasks.md | `openspec/changes/archive/2026-10-19-session-tokens-and-web-layer/tasks.md` | presente; 15 de 15 tareas `[x]`, 0 abiertas (31 casillas `- [x]` en total) |
| apply-progress.md | `openspec/changes/archive/2026-10-19-session-tokens-and-web-layer/apply-progress.md` | presente (instantánea intermedia) |
| verify-report.md | `openspec/changes/archive/2026-10-19-session-tokens-and-web-layer/verify-report.md` | presente; antes sin versionar, se incorpora en el commit de archivo |

## Síntesis de implementación

El cambio se entregó en la cadena de pull requests de la parte 4b (la revisión citada en las advertencias es la del PR #129; los últimos merges son #144, #145 y #146). Su alcance: núcleo de tokens de acceso (JWS EdDSA con `kid` obligatorio), anillo de claves, filtro de autenticación `Bearer`, principal, adaptadores de `CurrentInstitutionProvider` y `SessionValidity`, migración `V8` de familias y tokens de refresco, emisión, rotación con ventana de gracia de 10 segundos, detección de reutilización, revocación de todas las familias al restablecer la contraseña y la ruta `GET /api/v1/auth/sessions/current`.

### Verificación de cierre

Fuente: `verify-report.md`, verificado en `0d16c40`. El branch no contiene commits de código posteriores a esa base, así que estos números siguen siendo el estado final.

- Comando: `./mvnw -B verify -Pmutation-gate` (código de salida 0, `BUILD SUCCESS`).
- Surefire: kernel 186 pruebas y app 1 883, 0 fallos, 0 errores, 0 omitidas.
- Failsafe: 420 pruebas, 0 fallos.
- JaCoCo: app global 98,1 % de líneas y 94,0 % de ramas (mínimo 80 %); `identity.domain` 98,2 % de líneas y 100 % de ramas (mínimo 95 %).
- PIT: kernel 99 % (192 de 194); app 94 % (558 de 594), umbral 80 %.
- Escenarios trazados: 247 de 247 (identity 124, web-edge 68, build-integrity 43, organization 12). 242 con prueba automática y 5 con evidencia documental (I135, I136, I137, I148 y WE32). BI30, BI31 y BI33 se cubren con la configuración de la construcción.
- CI: el run `39370368796` del workflow `ci` sobre `0d16c40` terminó con `success`. Los jobs de backend y de frontend ejecutaron `actions/setup-node` con `node-version-file: .nvmrc` (`.nvmrc` = `24`). No se leyó la versión exacta de Node impresa en el log.

## Fusión de especificaciones

Los cuatro deltas se fusionaron en las especificaciones principales con el comando nativo `gentle-ai sdd-archive-compose`. Los cuatro dominios ya existían en `openspec/specs/`, así que ninguno se copió como especificación completa.

| Dominio | Requisitos antes | ADDED | MODIFIED | REMOVED | Requisitos después | Escenarios después | Líneas después |
|---------|------------------|-------|----------|---------|--------------------|--------------------|----------------|
| identity | 46 | 29 | 3 | 4 | 71 | 205 | 2 431 |
| web-edge | 42 | 9 | 8 | 2 | 49 | 168 | 1 639 |
| build-integrity | 67 | 9 | 2 | 0 | 76 | 211 | 2 306 |
| organization | 14 | 2 | 1 | 0 | 16 | 51 | 552 |

### Ajustes técnicos de la fusión (divulgados)

El comando nativo rechazó dos entradas de los deltas. La fusión se hizo con los ajustes siguientes, que no cambian el contenido de los requisitos fusionados.

1. **Encabezados de requisito.** El comando espera `### Requirement:`, y las especificaciones del repositorio usan `### Requisito:` (todas las de `openspec/specs/`, sin excepción). Se generaron copias de trabajo fuera del repositorio con el encabezado traducido, se fusionó sobre esas copias y la salida se devolvió a `### Requisito:` con la sustitución inversa. Ninguna de las dos transformaciones toca el cuerpo de los requisitos.
2. **Nota `(Reason: ...)` de los REMOVED.** El comando exige esa nota en una sola línea. En `identity` y `web-edge` está repartida en varias líneas. Solo la copia de trabajo se normalizó a una línea. Los requisitos REMOVED no se emiten en la salida, así que su texto no entra en la especificación principal. El delta archivado no se alteró.

Las transformaciones se verificaron por bloques de requisito, con un script de comprobación independiente: cada requisito no tocado es byte-idéntico al de `main` anterior (commit `0d16c40`); cada ADDED y MODIFIED es byte-idéntico al bloque del delta, salvo los tres citados en el punto 3; cada REMOVED está ausente; el preámbulo no cambió; los conteos cuadran con la tabla.

### Redacción de I108, I128 e I143 (advertencia 3)

En `openspec/specs/identity/spec.md` se cambió, en los tres escenarios, la mención de `CyclicBarrier` por el mecanismo que usan las pruebas:

- **[I143]** «…sincronizadas con `CountDownLatch` y una espera acotada sobre `pg_stat_activity`».
- **[I108]** «…sincronizadas con `CountDownLatch` y una espera acotada sobre `pg_stat_activity`».
- **[I128]** «…sincronizadas con `CountDownLatch` y una espera acotada sobre `pg_stat_activity`».

Las pruebas (`RefreshRotationConcurrencyIT`) usan `CountDownLatch`, con `await` de 30 segundos y una comprobación de bloqueo sobre `pg_stat_activity` dentro de un límite de tiempo.

El delta archivado conserva la redacción original. Es la única diferencia entre el delta y la especificación principal, y se limita a esas tres líneas. El escenario I95 de `identity`, que también menciona `CyclicBarrier`, no figura en esta advertencia y no se modificó; su mecanismo no se verificó en este archivo.

### Evidencia de la fusión (`diff -r`)

Cada especificación principal se comparó con la copia de trabajo fusionada. Salida literal de cada comparación:

```
$ diff -r <copia de trabajo identity> openspec/specs/identity/spec.md
(salida vacía)
$ diff -r <copia de trabajo web-edge> openspec/specs/web-edge/spec.md
(salida vacía)
$ diff -r <copia de trabajo build-integrity> openspec/specs/build-integrity/spec.md
(salida vacía)
$ diff -r <copia de trabajo organization> openspec/specs/organization/spec.md
(salida vacía)
```

## Movimiento del cambio al archivo

- **Origen:** `openspec/changes/session-tokens-and-web-layer`
- **Destino:** `openspec/changes/archive/2026-10-19-session-tokens-and-web-layer`
- **Mecanismo:** `cp -R` de instantánea previa, después `git mv` del directorio. El origen ya no existe.
- **Readback (instantánea previa contra el destino), salida literal:**

```
[diff -r exit 0: no differences]
```

## Corrección solicitada en la copia archivada: `tasks.md`

Por indicación del propietario, la línea de estado obsoleta de `tasks.md` se corrigió en la copia archivada. Se hizo después del readback del movimiento. Es la única excepción a la copia byte a byte del archivo. Salida literal de la comparación con la copia previa a la corrección:

```
6c6
< - **Estado:** pendiente de aprobación del propietario
---
> - **Estado:** completado y archivado el 2026-10-19 (corrección de archivo: la línea original, «pendiente de aprobación del propietario», era de la fase de tareas y quedó obsoleta al cerrar el cambio; el resto del documento no se modifica)
```

No se tocó ninguna casilla ni ningún otro párrafo.

## Nota en la documentación de seguridad (advertencia 2)

Se añadió una nota editorial fechada el 2026-10-19 al final de la sección 4.7 de `docs/03-seguridad.md`, después de las notas existentes. El texto original de la fila «Efecto» no se reescribió. La nota precisa que, tras un cambio de contraseña, un token de acceso ya emitido puede seguir aceptándose hasta 10 minutos en las rutas que no declaran `LIVE_SESSION` (decisión 7 e I118). Las rutas `LIVE_SESSION` rechazan el token en cuanto la familia está revocada (I117).

## Condiciones duras

Fuente: `apply-progress.md` (tabla de condiciones duras, I137) y la nota «quinto corte» de `openspec/changes/foundations-plan/exploration.md`. Esas fuentes se consultaron tal como están; no se reescribieron.

| Condición | Estado al archivar | Dueño |
|-----------|--------------------|-------|
| **H1**: control por IP antes del inicio de sesión (2026-09-25) | Abierta, transferida | `session-endpoints-cookie-and-csrf` |
| **H2**: revocar todas las familias al restablecer (2026-10-03) | **Cerrada** por la prueba I24 (`PasswordResetRevokesSessionsIT`) | este cambio |
| **H3**: 10 solicitudes por hora por IP en la solicitud de restablecimiento (2026-10-03) | Abierta, transferida | `password-recovery-endpoints` |
| **H4**: respuesta HTTP uniforme de la recuperación y puerta de tiempo | Abierta, transferida | `password-recovery-endpoints` |
| **Condición 3**: CSRF de doble envío antes de la primera cookie (2026-10-04) | Abierta, transferida | `session-endpoints-cookie-and-csrf` |

Este cambio no entrega ningún endpoint de inicio de sesión, de recuperación ni cookie, así que H1, H3, H4 y la condición 3 no se activan aquí y no se declaran cerradas.

## Advertencias del informe de verificación

Fuente: `verify-report.md` (fecha de verificación 2026-10-19, commit `0d16c40`).

1. **Seguimientos de la revisión del PR #129.** Se transfieren a `session-endpoints-cookie-and-csrf` como dueño:
   - (a) Una aserción directa de I24: que ningún token de la cuenta quede vigente. Hoy la cubren de forma indirecta `SessionLivenessIT.java:220` y `RotateRefreshTokenIT.java:161`.
   - (b) Una prueba del revocador entre instituciones. No existe todavía.
   - (c) Un serializador común para el JSON de auditoría. Hoy el JSON se arma por concatenación en `ResetPasswordWithToken.java:221-226` y `RotateRefreshToken.java:211,232,271,302`. Solo interpola constantes y enteros.
   - Estado: **transferida**, no ejecutada en este cambio.
2. **§4.7 de `docs/03-seguridad.md`.** Resuelta en este archivo con la nota fechada descrita arriba.
3. **Redacción `CyclicBarrier` frente a `CountDownLatch`.** Resuelta en este archivo con la corrección de I108, I128 e I143 en la especificación principal de `identity`.
4. **Contratos y Node 24.** Confirmado: el commit `0d16c40` tiene CI en `success` (run `39370368796`) con Node configurado desde `.nvmrc` (24). Los contratos de este cambio se generaron localmente con Node 22 (`apply-progress.md`); la comprobación de CI en Node 24 es la que aplica al cierre.

## Sugerencias del informe de verificación

1. **Mutantes sobrevivientes en `ClaimReader`** (8, entre ellos el rango de `epochSecond` en `ClaimReader.java:85`). Dueño: `session-endpoints-cookie-and-csrf`. Pendiente: pruebas de época negativa y fuera de rango, o justificación escrita de la equivalencia.
2. **`jti` en el barrido de secretos.** `SessionSecretsNotObservableTest.java:125-140` no incluye el `jti`, aunque las reglas comunes de `tasks.md` lo prohíben en registros. Dueño: `session-endpoints-cookie-and-csrf`.
3. **`@DisplayName` con identificadores de escenario.** 67 identificadores no aparecen en las pruebas. Sin dueño asignado. Etiquetarlos haría la trazabilidad mecánica.
4. **Línea de estado obsoleta en `tasks.md`.** Corregida en la copia archivada (sección anterior).

## Riesgos y limitaciones

- **Herramienta de fusión.** `gentle-ai sdd-archive-compose` no admite los encabezados `### Requisito:` que usa el repositorio, y exige la nota `(Reason: ...)` en una sola línea. Los ajustes descritos arriba se aplicaron a copias de trabajo y se verificaron por bloques. Conviene que quien mantenga la herramienta tenga en cuenta ambos casos.
- **Excepción de byte-identidad.** La copia archivada de `tasks.md` difiere de la original en una línea, por indicación del propietario.
- **Roadmap (`openspec/changes/foundations-plan/`).** No se actualizó. Los dos archivos anteriores más recientes (`web-edge-foundations`, commit `d4c1967`, y `password-recovery-token`, commit `32d143b`) no tocaron el roadmap ni la documentación, así que no hay convención que seguir. El estado de la parte 4b ya figura en su nota «quinto corte».
- **Observaciones de Engram.** Los artefactos se leyeron desde sus rutas de archivo (modo hybrid: los localizadores son rutas, no temas). No se leyó ninguna observación de Engram como fuente. `verify-report.md` cita la observación de Engram `#622` (sugerencias de la revisión del PR #129); este informe no la leyó.
- **Lecturas.** `verify-report.md` completo; `tasks.md`, los deltas y los pasajes citados de `apply-progress.md`, `design.md` y `exploration.md` por secciones. Los demás contenidos se movieron sin lectura y se verificaron por `diff -r`.

## Trabajo abierto y próximos pasos

- **`session-endpoints-cookie-and-csrf` (S2):** H1, condición 3, advertencia 1 (a), (b) y (c), sugerencias 1 y 2.
- **`password-recovery-endpoints`:** H3 y H4.
- **`background-jobs-with-db-scheduler` (cambio 9):** condición 4, sin cambios.
- **Sugerencia 3:** sin dueño; decidir en la planificación de la siguiente fase.

## Commits de archivo

1. `docs(sdd): publish session-tokens-and-web-layer requirements into the main specs`: fusión de los cuatro deltas en `openspec/specs/`, con la redacción de I108, I128 e I143.
2. `docs(security): note the access-token window after a password change`: nota fechada en `docs/03-seguridad.md` §4.7.
3. `docs(sdd): archive session-tokens-and-web-layer`: movimiento del cambio, `verify-report.md` incorporado y este informe.
4. `docs(sdd): correct the stale status line in archived tasks`: línea de estado de `tasks.md` en la copia archivada.

Commits convencionales en inglés, sin atribución de herramientas de IA. No se abrió pull request ni se fusionó nada. La entrega queda bajo la política ordinaria del repositorio.

---

**Fecha de archivo:** 2026-10-19
**Identificador de cambio:** `session-tokens-and-web-layer`
**Línea de base:** `main` en `0d16c40`
