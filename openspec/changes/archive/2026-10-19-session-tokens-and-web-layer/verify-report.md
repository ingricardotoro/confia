```yaml
schema: gentle-ai.verify-result/v1
verdict: pass_with_warnings
blockers: 0
critical_findings: 0
warnings: 4
suggestions: 4
scenarios: 247/247
test_command: ./mvnw -B verify -Pmutation-gate
test_exit_code: 0
test_output_hash: sha256:7ec353fd35d7d6dcaa701131cc9c29d4fe98dd4f9e368f23d6380719268d36eb
commit: 0d16c405508f78803e8d0b42fc6d25f28ca46a39
```

## Informe de verificación: `session-tokens-and-web-layer` (S1, parte 4b)

- **Fecha:** 2026-10-19
- **Commit verificado:** `0d16c40` en `main` (árbol limpio, sin cambios de código durante la verificación)
- **Modo:** TDD estricto (`openspec/config.yaml`); la evidencia de rojo y verde es la registrada en `apply-progress.md`, no se
  reconstruye aquí
- **Comando real:** `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" ./mvnw -B verify -Pmutation-gate`, desde `apps/api`

### Veredicto

**PASS WITH WARNINGS.** Ningún hallazgo crítico. Las 15 tareas (con sus costuras) están `[x]`. La construcción completa con la
puerta de mutación termina en verde. Las advertencias son de trazabilidad y de documentación, y el informe de archivo debe
recogerlas. Ninguna impide archivar.

### Resultado de la ejecución

| Comprobación | Resultado |
|---|---|
| Surefire | kernel 186 y app 1 883 pruebas, 0 fallos, 0 errores, 0 omitidas |
| Failsafe | 420 pruebas, 0 fallos (incluye `CurrentSessionEndpointIT` 12/12) |
| JaCoCo | «All coverage checks have been met» en kernel y app; app global: 98,1 % de líneas y 94,0 % de ramas (mínimo 80); `identity.domain`: 98,2 % de líneas y 100 % de ramas (mínimo 95) |
| PIT (`mutation-gate`) | kernel: 99 % (192 de 194); app: 94 % (558 de 594), umbral 80 |
| ArchUnit y Spring Modulith | verdes dentro de Surefire |
| Duración | 13 min 35 s, `BUILD SUCCESS`, código de salida 0 |

### Cobertura de escenarios

247 de 247 escenarios trazados (identity 124, web-edge 68, build-integrity 43, organization 12). De ellos, 242 tienen una
prueba automática y 5 se cubren con evidencia documental (I135, I136, I137, I148 y WE32). BI30, BI31 y BI33 se cubren con la
configuración de la construcción: la puerta de PIT se ejercitó en esta ejecución y la prohibición de dependencias está en
`apps/api/pom.xml:396-405`. 67 identificadores no aparecen escritos en el código de pruebas. Se comprobó por muestreo que
tienen una prueba de comportamiento (por ejemplo, BI02 a BI09 en `RefreshTokenSchemaIT`, y WE01 a WE42 en
`AccessTokenAuthenticationFilterTest` y en las pruebas de la cadena).

Se revisaron a fondo estos escenarios:

- **Rotación, gracia y reutilización:** I91 a I114, I141 a I145 y I95 (`RotateRefreshTokenIT`, `RefreshGraceWindowIT`, `RefreshRotationConcurrencyIT`).
- **Revocación al restablecer:** I24 e I122 a I125.
- **Vigencia de sesión:** I115 a I118, I127 e I146.
- **Ruta de la sesión actual:** I129 a I134, WE29, BI24 a BI27, y OR05 y OR10 por HTTP.
- **Secretos no observables:** I85 e I86.

### Decisiones de diseño contra el código

- **Decisión 5:** se cumple en `AccessTokenAuthenticationFilter.java:78-168`, con los pasos 1 a 4, `clearContext` en `finally` y
  `Bearer` estricto.
- **Decisión 7:** se cumple en `SessionLiveness.java:46-58`. Es de solo lectura, usa el contexto del actor y falla cerrado.
- **Decisión 10:** se cumple en `RotateRefreshToken.java:142-188`.
  - Los pasos 1 a 7 van en el orden del diseño.
  - Todo rechazo es un valor devuelto.
  - El token de acceso se firma después de confirmar.
- **Decisión 11:**
  - La evaluación de la gracia está en `RotationDecision.java:65-69`.
  - La AAD es la fila del predecesor (`RotateRefreshToken.java:197-199`, `243-252`).
- **Decisión 13:**
  - El controlador está en `CurrentSessionController.java`.
  - `override-with-generic-response: false` está en `application.yml:83`.
  - La instantánea contiene una sola operación (`200`, `401` con `WWW-Authenticate`, `500`) y el esquema `bearerAuth`.
- **Concurrencia:**
  - Bloqueo `FOR UPDATE` de la familia en `JooqRefreshTokenFamilyRepository.java:69-72`.
  - Consumo condicional `consumed_at IS NULL` en `JooqRefreshTokenRepository.java:71-83`.
  - Revocación condicional por reutilización en `JooqRefreshTokenFamilyRepository.java:116-125`.
  - `ORDER BY id FOR UPDATE` del revocador en `JooqAccountSessionRevoker.java:41-48`.
  - Disparadores de solo avance de V8 en `V8__create_identity_refresh_token_tables.sql:147-201`.

### Reglas no negociables

- **Registros:** el código nuevo no registra nada. No se usa `System.out` ni `printStackTrace`.
- **SQL:** solo se usa la DSL de jOOQ, salvo la plantilla fija `cast({0} as inet)` con valor enlazado, que está en la lista aprobada.
- **jOOQ:** no aparece fuera de `infrastructure`.
- **Transacciones:** no hay `@Transactional`.
- **Salida:** la salida pasa por el DTO `CurrentSessionResponse`.
- **Auditoría:** se registran la emisión, la rotación, el rechazo, la reutilización y la revocación al restablecer. La lectura de
  la sesión no escribe, como fija I134.

### Hallazgos

**CRITICAL:** ninguno.

**WARNING**

1. **Las sugerencias de la revisión del PR #129 solo están en Engram** (observación #622), no en un artefacto del repositorio.
   - (a) `PasswordResetRevokesSessionsIT.java:56-98`, prueba de I24: no afirma de forma directa «ningún token suyo sigue
     vigente». Lo cubren de forma indirecta `SessionLivenessIT.java:220` y `RotateRefreshTokenIT.java:161`.
   - (b) No existe una prueba del revocador entre instituciones.
   - (c) El JSON de auditoría se arma por concatenación en `ResetPasswordWithToken.java:221-226` y
     `RotateRefreshToken.java:211,232,271,302`. Hoy solo interpola constantes y enteros, pero no hay un serializador común.
   - **Corrección:** listar las tres en el informe de archivo con un dueño (S2 o un cambio de mantenimiento).
2. **`docs/03-seguridad.md:548` (§4.7) dice que cambiar la contraseña «cierra todas las sesiones activas».** Los tokens de acceso
   siguen valiendo hasta 10 minutos en las rutas `TOKEN_ONLY`, como fijan I118 y la decisión 7. Esa ventana no figura entre los
   pendientes de `apply-progress.md`.
   - **Corrección:** añadir una nota fechada en §4.7 o registrarla en el informe de archivo con un dueño.
3. **Redacción `CyclicBarrier` frente a `CountDownLatch`.** I143 (`specs/identity/spec.md:585`), I108 (línea 721) e I128 (línea 960)
   piden `CyclicBarrier`. `RefreshRotationConcurrencyIT` usa `CountDownLatch` con una condición acotada sobre `pg_stat_activity`,
   un mecanismo más determinista. Esta desviación no está registrada.
   - **Corrección:** anotarla en el informe de archivo y ajustar la redacción al fusionar la especificación.
4. **Los contratos no se generaron con Node 24.** Se generaron con Node 22 ejecutando orval, tsc y vitest de forma directa
   (`apply-progress.md:1844`). Está registrado. CI usa `.nvmrc` = 24.
   - **Corrección:** confirmar que CI queda en verde en `main` para `0d16c40` antes de archivar, y anotarlo en el informe de
     archivo.

**SUGGESTION**

1. Hay 8 mutantes sobrevivientes en `ClaimReader`. Uno es el rango de `epochSecond` (`ClaimReader.java:85`). Conviene añadir
   pruebas de época negativa y fuera de rango, o justificar la equivalencia por escrito.
2. `SessionSecretsNotObservableTest.java:125-140` no incluye el `jti` en el mapa de secretos, aunque las reglas comunes de
   `tasks.md` lo prohíben en registros.
3. 67 identificadores de escenario no se nombran en las pruebas. Etiquetarlos con `@DisplayName` haría la trazabilidad mecánica.
4. La cabecera de `tasks.md:6` sigue diciendo «pendiente de aprobación del propietario».

### Condiciones duras

- H1 y la condición 3 están **abiertas y transferidas** a `session-endpoints-cookie-and-csrf`.
- H3 y H4 están **abiertas y transferidas** a `password-recovery-endpoints`.
- H2 está **cerrada** por I24 (`PasswordResetRevokesSessionsIT`).

El reparto está registrado en `apply-progress.md:1881-1893` y en la nota «quinto corte» de
`openspec/changes/foundations-plan/exploration.md:275`.

### Tareas

15 de 15 tareas `[x]`, con todas sus costuras. Esta verificación no modificó ninguna casilla.

### Limitaciones

- La verificación se hizo por ejecución completa y por lectura del código y de las pruebas. La revisión de escenarios fue por
  muestreo profundo, no exhaustiva. Para el resto se aceptó el barrido de trazabilidad de `apply-progress.md`.
- No se repitieron las demostraciones deliberadas de rojo.
- No se comprobó el estado de CI.

### Siguiente paso

`sdd-archive`. El informe de archivo debe incluir las advertencias 1 a 4.
