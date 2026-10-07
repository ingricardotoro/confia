# Informe de archivo: Borde web del proceso administrativo

- **Cambio:** `web-edge-foundations` (F0, cambio 7, parte 4a)
- **Archivado:** 2026-10-06
- **Estado de cierre:** completo — todas las tareas implementadas, verificadas y fusionadas a `main`

## Artefactos

| Artefacto | Ubicación | Estado |
|-----------|-----------|--------|
| proposal.md | `openspec/changes/archive/2026-10-06-web-edge-foundations/proposal.md` | presente |
| design.md | `openspec/changes/archive/2026-10-06-web-edge-foundations/design.md` | presente |
| specs/web-edge/spec.md (nueva capacidad) | `openspec/changes/archive/2026-10-06-web-edge-foundations/specs/web-edge/spec.md` | presente |
| specs/build-integrity/spec.md (delta) | `openspec/changes/archive/2026-10-06-web-edge-foundations/specs/build-integrity/spec.md` | presente |
| tasks.md | `openspec/changes/archive/2026-10-06-web-edge-foundations/tasks.md` | presente |
| apply-progress.md | `openspec/changes/archive/2026-10-06-web-edge-foundations/apply-progress.md` | presente |
| verify-report | N/A | no entregado (verificación opcional) |

## Síntesis de implementación

Las 26 tareas del cambio fueron completadas en 25 pull requests encadenados y fusionados a `main`, más una tarea de cierre (6.1) sin PR propio:

### Organización de las tareas

El cambio se partió en 25 PR fusionados a `main` por decisión de estrategia de entrega `stacked-to-main`:

- **PR #81-#85** (5 PR): Fase 1, cableado de producción del proceso administrativo
  - Tareas 1.1 y 1.2: `DataSource`, `shared`, identidad como beans, secretos con fail-fast
  
- **PR #86-#92** (7 PR): Fase 2, borde de seguridad, origen y reglas
  - Tareas 2.1a-2.1c: Problem Details, catálogo es-HN, cabeceras base, Spring Security, portal
  - Tareas 2.2a-2.2b: Lo que Tomcat rechaza, lista blanca, rutas, registros sin secretos
  - Tareas 2.3a-2.3d: Direcciones de cliente, proxies de confianza, origen ligado, auditoría

- **PR #93-#94** (2 PR): Fase 2 (continuación), traductor de excepciones
  - Tareas 2.4a-2.4b: Traductor de Problem Details, lista de campos, violaciones
  - Tarea 2.5: Reglas W1-W3 del borde web

- **PR #95-#100** (6 PR): Fase 3, limitador por IP en memoria
  - Tareas 3.1a-3.1c: Puerto, política, adaptador en memoria; tabla acotada; estrés concurrente
  - Tareas 3.2a-3.2c: Códigos `429` y `503`, interceptor, configuración y cableado
  - Tarea 3.2c incluye ampliación de excepción de tamaño (+30 líneas)

- **PR #101-#104** (4 PR): Fase 4, materialización del retardo de autenticación
  - Tareas 4.1a-4.1c: Materializador, configuración, prueba de ejecución, regla W4

- **PR #105-#106** (2 PR): Fase 5, borde HTTP de la idempotencia
  - Tareas 5.1a-5.1b: Anotación, interceptor, códigos, cableado; prueba de ejecución
  - Tarea 5.1b incluye ampliación de excepción de tamaño (+4 líneas)

### Decisiones finales del propietario (notas fechadas 2026-10-06)

- **Partición de tareas:** El propietario aprobó excepciones de tamaño para 6 tareas (2.2b +27 líneas, 3.2c +30 líneas, 5.1b +4 líneas), conservando el cambio en 25 PR sin reducciones de contenido
- **Corrección S-3 (CLAUDE.md §11):** Registros `INFO` de Tomcat sobre peticiones mal formadas: `SensitiveLogGuard` ahora deniega también `org.apache.coyote.http11.Http11Processor` en `INFO`

### Verificación final (tarea 6.1, cierre 2026-10-06)

**Compilación y pruebas:**

- Surefire: 186 pruebas unitarias + 1 200 nuevas = 1 386 total (todas pasan)
- Failsafe: 250 pruebas de integración (todas pasan)
- JaCoCo: 97,8 % de cobertura global; módulo `kernel` 99 %, paquete `domain` 94 %
- PIT (mutación): 99 % en `kernel`, 94 % en paquete `domain` de cada módulo
- ArchUnit: reglas W1-W5 pasan con fixtures permanentes negativos
- Spring Modulith: verificación completa en verde
- OpenAPI: 16 pruebas de contrato en verde, instantánea sin cambios
- Instantánea de rutas del portal: sin cambios
- DependencyConvergence: sin excepciones nuevas
- Análisis de vulnerabilidades: sin alta o crítica atribuible a este cambio

**Trazabilidad de escenarios:**

- 166 escenarios nombrados en requisitos de `web-edge` y `build-integrity`
- 166 escenarios con tarea identificada en `tasks.md`
- 0 escenarios huérfanos

**Seguimientos registrados en `apply-progress.md`:**

- S-1: Observabilidad — registro `ERROR` de `ProblemExceptionHandler.unexpected` imprime excepciones completas (cambio de observabilidad)
- S-2: Observabilidad — filtro de mensajes de excepción (cambio de observabilidad)
- S-3: Corrección en cierre — registros `INFO` de Tomcat (CLAUDE.md §11), resuelto
- S-4: Idempotencia — `@IdempotentWrite` aún no es obligatoria (cambio 8)
- S-5: Idempotencia — `IdempotentRequestHandler` pendiente de primera prueba con BD (cambio 8)

**Hallazgos de revisión (notas de `design.md`):**

- I-1, I-2, I-3: Decisiones de arquitectura sobre métrica de puerto, política del limitador, modelo ingenuo (resueltas en diseño)
- S-1, S-2: Proxies de confianza y bits de host (resueltos en tarea 2.3b)
- S-3: Cabecera `Retry-After` redondeado hacia arriba (resuelto en tarea 3.2c)

## Fusión de especificaciones

### Capacidad nueva: web-edge

**Origen:** `openspec/changes/archive/2026-10-06-web-edge-foundations/specs/web-edge/spec.md`  
**Destino:** `openspec/specs/web-edge/spec.md`

| Métrica | Valor |
|---------|-------|
| Requisitos nuevos | 42 |
| Escenarios | 132 |
| Líneas del archivo | 1 184 |

La especificación es byte-idéntica a la copia: `cp -R` verificado con `diff -r`.

### Delta en build-integrity

**Origen:** `openspec/changes/archive/2026-10-06-web-edge-foundations/specs/build-integrity/spec.md`  
**Destino:** `openspec/specs/build-integrity/spec.md`

#### Cambios aplicados

| Sección | Requisitos | Escenarios | Acción |
|---------|-----------|-----------|--------|
| ADDED | 7 | 20 | Apendidos |
| MODIFIED | 4 | 14 nuevos (reemplazan 10 antiguos) | Reemplazados en su lugar |
| REMOVED | 1 | 1 | Eliminado |

#### Conteos de especificación

| Métrica | Antes | Después | Cambio |
|---------|-------|---------|--------|
| Requisitos | 61 | 67 | +7 (ADDED) − 1 (REMOVED) |
| Escenarios | 151 | 174 | +20 (ADDED) + (14 − 10) (MODIFIED) − 1 (REMOVED) = +23 |
| Líneas del archivo | 1 651 | 1 890 | |

**Verificación de integridad de la fusión.** Primero se hizo una fusión manual. Al revisarla se vio que había borrado seis requisitos:
los dos MODIFIED del contexto del portal y del trabajador  y cuatro requisitos que el delta no toca (db-scheduler vacuo  subpaquetes de
`bootstrap`  referencias a clases de entrada y prueba negativa permanente del inspector). El conteo de escenarios coincidía por
casualidad. Esa fusión se descartó y la especificación se reconstruyó por guion  requisito a requisito  a partir de la versión de `main`
y del delta. La verificación del resultado comprobó:

- el preámbulo  sin cambios;
- cada requisito que el delta no toca  byte-idéntico al de `main`;
- cada requisito MODIFIED  igual al texto del delta y en su lugar;
- los siete ADDED  iguales al delta y añadidos al final;
- el requisito REMOVED  ausente;
- ningún marcador de delta en la especificación principal;
- conteos: 61 + 7 − 1 = 67 requisitos y 151 + 20 + 4 − 1 = 174 escenarios.

`openspec/specs/web-edge/spec.md` es idéntica a la copia del cambio.

## Movimiento del cambio al archivo

**Origen:** `openspec/changes/web-edge-foundations`  
**Destino:** `openspec/changes/archive/2026-10-06-web-edge-foundations`

Todos los artefactos se movieron con `git mv` sin alteración de contenido. Verificación post-movimiento:

- ✓ El árbol de origen (`openspec/changes/web-edge-foundations`) se fue completamente
- ✓ El árbol de destino es byte-idéntico a la instantánea previa al movimiento (validado con `diff -r`)
- ✓ Todos los archivos permanecen intactos: proposal.md, design.md, exploration.md, tasks.md, apply-progress.md, specs/

## Riesgos y limitaciones

**Ningún riesgo se materializó:** Todos los pasos de la sección 8 del `design.md` convergieron correctamente, las excepciones de tamaño fueron aprobadas por el propietario, y la verificación está completa en verde.

**Limitaciones operacionales conocidas, abiertas para cambios posteriores:**

- **Observabilidad del ERROR de excepciones** (S-1): El registro `ERROR` de `ProblemExceptionHandler.unexpected` imprime la excepción completa (incluido el mensaje de SQL de PostgreSQL con claves y valores), que viola CLAUDE.md §11. Seguimiento: cambio de observabilidad (F1)
- **Filtro de mensajes de excepción** (S-2): No hay filtro centralizado de mensajes de excepción en registros. Seguimiento: cambio de observabilidad (F1)
- **Prueba de primer endpoint con base de datos** (S-5): El manejador `IdempotentRequestHandler` no se prueba con base de datos real hasta el cambio 8 (`staff-authentication-mfa-sessions`), que trae el primer endpoint con transacción
- **`@IdempotentWrite` aún no es obligatoria** (S-4): El interceptor `RateLimitInterceptor` no rechaza un endpoint que no declare `@IdempotentWrite`. Obligatoriedad prevista en cambio 8

## Entrega de autoridad final

Per la sección de Final-State Authority de `sdd-archive` SKILL.md:

- **Estado reportado en tasks.md:** 26 tareas (25 de PR + 1 de cierre), todas marcadas `[x]` completas
- **Estado reportado en apply-progress.md:** all_done, 25/25 PR fusionados a `main`, verificación completa en verde
- **Hechos finales de la sesión del orquestador:** Todos los 25 PR fusionados a `main` (merge commit b1359b5 de PR #107 cierre), verificación completa en verde con Surefire, Failsafe, JaCoCo, PIT, ArchUnit, Spring Modulith
- **Verificación:** No se entregó verify-report; la verificación es opcional y no bloquea el archivo

Todas las fuentes coinciden: el cambio está completo, verificado y listo para su archivo.

## Commits de archivo

Se crearán dos commits convencionales en inglés:

1. `docs(sdd): publish web-edge requirements and sync build-integrity into the main specs`  
   Copia la especificación de `web-edge` a `openspec/specs/web-edge/spec.md` y fusiona el delta de `build-integrity` en `openspec/specs/build-integrity/spec.md`.

2. `docs(sdd): archive web-edge-foundations`  
   Mueve la carpeta del cambio de `openspec/changes/web-edge-foundations` a `openspec/changes/archive/2026-10-06-web-edge-foundations` con `git mv`.

En convención Conventional Commits, sin atribución de herramienta de IA.

## Próximas recomendaciones

**Cambio 8 (`staff-authentication-mfa-sessions`):** Sigue el roadmap. Depende de las capacidades `web-edge` y `build-integrity` entregadas aquí:
- Usa el borde HTTP de seguridad (cadena, Problem Details, cabeceras, origen)
- Implementa el primer endpoint administrativo (`POST /auth/session`) con transacción
- Prueba `IdempotentRequestHandler` con base de datos real
- Entrega la matriz de roles que habilita endpoints del portal

**Cambio de observabilidad (F1):** Resuelve S-1 y S-2 (filtro de mensajes de excepción y registro `ERROR`).

---

**Archivo generado el:** 2026-10-06  
**Cambio del roadmap:** F0, cambio 7 (parte 4a)  
**Identificador de cambio:** `web-edge-foundations`  
**Línea de base de fusión:** commit b1359b5 (PR #107, merge del último PR de la cadena 25 a `main`)
