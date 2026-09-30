# Progreso de aplicación: `frontend-monorepo-and-contracts-pipeline`

- **Corte en curso:** 3b completo (tareas 2.1 a 2.5 en verde); 3a fusionado en el PR #64; siguiente, 3c
- **Entorno:** sesión remota sin Docker ni JDK 25. Todo el corte 3a se verificó en la integración
  continua de la rama `change/frontend-monorepo-and-contracts-pipeline`, que parte de un checkout
  limpio con JDK 25 y Docker. Cada evidencia de abajo nombra la ejecución de la que sale.

---

## Tarea 1.1 — Sondas S1 y S2

**S1.** springdoc-openapi **3.1.1**. Motivo: su POM publicado declara como padre
`spring-boot-starter-parent` **4.1.0**, la misma línea que este proyecto (4.1.1). La 3.0.3 se
construyó sobre 4.0.5. Consultado en Maven Central el 2026-09-30. Ambos puntos de entrada arrancan
con springdoc y sin `DataSource`, y sirven `/v3/api-docs` con el perfil `local`: lo demuestra
`OpenApiExposureByProfileTest`, en verde desde la ejecución 214.

**S2.** El documento normalizado es determinista: `theNormalizedDocumentIsDeterministic` genera dos
veces el documento de `admin` y compara los bytes. En verde desde la ejecución 214. La normalización
ordena las claves, fija la sangría, convierte los finales de línea a LF y quita el bloque `servers`,
que depende del puerto aleatorio. `.gitattributes` (`* text=auto eol=lf`) mantiene la instantánea en
LF también en Windows.

## Tarea 1.2 — Swagger apagado por defecto

`application.yml` apaga el documento y Swagger UI y fija OpenAPI 3.1 (`springdoc.api-docs.version:
openapi_3_1`). Solo `application-local.yml` y `application-preprod.yml` los encienden.
`OpenApiExposureByProfileTest` comprueba los dos procesos con cuatro perfiles: `local` y `preprod`
responden; `prod` y un perfil inexistente dan 404 en las dos rutas.

## Tarea 1.3 — Esquemas transversales, y la caducidad de la capa Web

`ContractSchemas` publica `Money` y `ProblemDetail` en los dos documentos. `ProcessApiInfo` titula
cada documento con una propiedad que el lanzador fija por proceso. Las pruebas
`moneyTravelsAsTwoRequiredStringsAndNeverAsANumber` y
`problemDetailDeclaresTheRfc9457FieldsAndTheTraceId` están en verde.

**Punto de parada alcanzado y resuelto por el propietario.** `EmptyShouldExceptionInventoryTest`
falló, como pretende ADR-0018: `ContractSchemas` es la primera clase de producción en un paquete
`web`, que es justo la condición de caducidad que ADR-0020 §3 fijó para la capa `Web`. Se consultó
y el propietario eligió retirar la excepción (2026-09-30). En un solo commit: `optionalLayer("Web")`
pasa a `layer("Web")`, el inventario queda vacío y ADR-0020 recibe una nota fechada. Spring Modulith
aceptó el paquete nuevo con su `@NamedInterface` a la primera.

**Desviación del diseño, dicha aquí.** El diseño ponía el título en un `@Bean` por proceso. Los
tres puntos de entrada comparten el paquete `com.confia.bootstrap` y cada uno lo escanea, así que
un bean declarado en uno podría acabar en los contextos de los otros. Por eso el título lo fija el
lanzador (`ConfiaApplication.launch`) con una propiedad. **Riesgo más amplio, fuera de este
cambio:** ese mismo escaneo cruzado podría hacer que el contexto del portal cargue la configuración
de `AdminApplication` el día que esta registre un módulo administrativo, lo que va contra
ADR-0003. Se propone tratarlo en un cambio propio antes del primer módulo con `web`.

**Otra desviación menor:** las dos pruebas viven en `com.confia.bootstrap` (test), no en
`com.confia.architecture` como decía el diseño, porque los puntos de entrada son de paquete.

## Tarea 1.4 — Instantáneas y su puerta

La primera ejecución con la puerta (211) falló a propósito sin instantáneas e imprimió los dos
documentos. Se revisaron: OpenAPI 3.1.0, sin operaciones, con `Money` y `ProblemDetail`, iguales
salvo el título. Se comprometieron copiados byte a byte (`d43c8c5`). La ejecución 214 quedó en verde.

**La demostración obligatoria queda como prueba permanente**, en lugar de un empuje roto y revertido:
- `aSnapshotAlteredByHandFailsNamingTheFirstDifferenceAndIsLeftUntouched`: la misma comparación,
  sobre una copia alterada a mano, falla nombrando `$.info.title` y deja el archivo intacto.
- `theUpdateFlagRewritesTheSnapshotAndStillFails`: con la opción de actualización, la instantánea se
  reescribe y la prueba igualmente falla.

Una aserción que la puerta obliga a decir: con cero rutas en ambos documentos, la comprobación de
que el portal no sirve ninguna ruta administrativa **es cierta de vacío hoy**. Tendrá contenido con
el primer controlador.

## Tarea 1.5 — Integración continua, criterio 7 y medición

- `apps/api/app/target/openapi/` se sube dentro del artefacto `backend-reports`.
- El criterio de salida 7 de F0 queda marcado como cerrado en `docs/09-roadmap-y-fases.md`, con la
  prueba que lo demuestra.
- **Diff del corte**, sin `openspec/` ni las instantáneas: **+650 −28** en 19 archivos. Por debajo de
  800.
- **Verificación del corte**, CI del PR `ingricardotoro/confia#64` sobre `d1ffbca`: `BUILD SUCCESS`;
  `OpenApiContractSnapshotTest` `Tests run: 10`, `OpenApiExposureByProfileTest` `Tests run: 8`,
  `EmptyShouldExceptionInventoryTest`, `LayeredArchitectureTest`, `SpringModulithVerificationTest` y
  `SuppressionCitesAdrTest` en verde; 343 unitarias y 162 de integración en `app`, cero fallos;
  cobertura cumplida; PIT de `domain` 94 %, igual que antes del corte.

---

## Corte 3b — monorepo, configuración compartida y contratos

**Entorno de este corte.** A diferencia de 3a, 3b no necesita JDK ni Docker y se verificó **en local**,
con Node 24.21.0 descargado de nodejs.org (suma SHA-256 verificada contra `SHASUMS256.txt`) y pnpm
12.8.1. El trabajo `frontend verify` de la integración continua llega con el corte 3c.

### Tarea 2.1 — Espacio de trabajo, versiones y dos correcciones al diseño

Versiones fijadas, cada una en un solo lugar (el `catalog:` de `pnpm-workspace.yaml`, salvo pnpm y
turbo), consultadas en el registro de npm el 2026-09-30:

| Herramienta | Versión | Motivo |
|---|---|---|
| Node | 24.21.0 (`engines` `>=24 <25`, `.nvmrc` `24`) | LTS activa (Krypton); Node 26 es la versión actual, todavía no LTS |
| pnpm | 12.8.1 (`packageManager`) | Última estable |
| Turborepo | 2.11.5 | Última estable |
| TypeScript | **6.0.3, no 7.0.2** | `typescript-eslint` 8.71 declara `typescript >=4.8.4 <6.1.0` |
| ESLint / `@eslint/js` / `typescript-eslint` | 10.11.0 / 10.0.1 / 8.71.0 | Últimas estables compatibles entre sí |
| Vitest | 5.0.2 | Declara `node ^24.0.0` |
| orval / Zod | 8.38.0 / 4.6.5 | Últimas estables |
| `@types/node` | 24.19.0 | La última de la línea 24, alineada con Node |

**Dos correcciones al diseño, encontradas al leer el `CHANGELOG` de pnpm 12 y confirmadas en la
práctica:**

1. **`onlyBuiltDependencies` ya no existe.** pnpm 11 lo reemplazó por `allowBuilds` y desde entonces
   lo **ignora en silencio**. Con el ajuste del diseño, la protección no habría hecho nada. Se usa
   `strictDepBuilds: true` con `allowBuilds`. Primera instalación: falló con `ERR_PNPM_IGNORED_BUILDS`
   por el `postinstall` de esbuild, que solo verifica un binario que ya llega como paquete
   opcional por plataforma. Se **deniega** explícitamente (`esbuild: false`): no se ejecuta ningún
   guion de instalación.
2. **`engine-strict` en `.npmrc` se ignoraría.** pnpm 12 solo toma de `.npmrc` las claves de
   credenciales y registros. `engineStrict: true` va en `pnpm-workspace.yaml`, y no se crea `.npmrc`.

**Demostraciones registradas:**
- **Versión de Node distinta.** Con Node 22.22.2 y sin `node_modules`: `ERR_PNPM_UNSUPPORTED_ENGINE …
  wanted: {"node":">=24 <25"} (current: {"node":"22.22.2"})`, código de salida 1. **Matiz:** si
  `node_modules` ya está al día, pnpm contesta «Already up to date» y no vuelve a comprobar el motor.
  La integración continua siempre instala desde cero, así que no le afecta.
- **Archivo de bloqueo desactualizado.** Tras añadir `left-pad` a un `package.json` sin tocar el
  archivo de bloqueo, `pnpm install --frozen-lockfile` falló nombrando la dependencia añadida, sin
  reescribir el archivo. Revertido.

**Algo que no se había previsto:** Turborepo 2.11 crea `AGENTS.md` en la raíz cuando detecta que lo
invoca un agente de IA. Se desactivó con `"agentGuidance": false` en `turbo.json`, opción documentada
en su `docs/reference/configuration.mdx`, y se borró el archivo. Las instrucciones del proyecto siguen
en `CLAUDE.md`.

### Tarea 2.2 — `packages/config`

`tsconfig.base.json` estricto, configuración plana de ESLint (`confiaEslint`, estricta con tipos) y
base de Vitest (`confiaVitest`). Su prueba, `tsconfig-base.test.ts`, afirma las cuatro opciones
estrictas: 4 de 4 en verde.

### Tarea 2.3 — Sonda S3 y `packages/contracts`

**S3, resultado.** Con la instantánea real, sin operaciones, el cliente Zod de orval no produce nada
(`index.ts` queda vacío). En cambio, `output.schemas` con `type: "zod"` sí genera desde los componentes
`Money` y `ProblemDetail`, cada uno como esquema Zod con sus tipos de entrada y salida. Se usa eso.
`index.ts` recibirá las operaciones con el primer controlador.

**Desviación menor del diseño:** los puntos de entrada `./admin` y `./portal` apuntan a dos módulos
escritos a mano, `src/admin.ts` y `src/portal.ts`, que solo reexportan lo generado. Un consumidor nunca
importa un archivo generado directamente. `apps/api/package.json` tiene el guion `verify`, que delega
en `./mvnw -B verify`.

### Tarea 2.4 — Pruebas de `packages/contracts`

8 de 8 en verde: 7 de ejecución y 1 de tipos.
- El importe como cadena se acepta en las dos superficies y como número se rechaza.
- También se rechazan más de cuatro decimales y una moneda en minúsculas.
- `ProblemDetail` acepta un error de RFC 9457 con `traceId`.
- `src/generated/` está ignorado por Git (`git check-ignore`).
- `money.test-d.ts` afirma que `amount` y `currency` son `string` en los dos contratos.

**Demostración registrada.** Con `amount` cambiado a `number` en la instantánea de `admin` y los
contratos regenerados:
- la verificación de tipos falló con `error TS2344: … '{ amount: "Expected: string, Actual: number"; …}'`;
- dos pruebas de ejecución de `admin` fallaron.

Restaurada la instantánea, todo vuelve a verde.

### Tarea 2.5 — Node 24 en `docs/05` y medición

- `docs/05-infraestructura-y-despliegue.md` pasa a Node 24 con una nota fechada:
  - la imagen de construcción queda sin dígest hasta el cambio que introduzca Renovate;
  - el ejemplo de integración continua lee `.nvmrc` y `packageManager`.
- Este corte no crea nada bajo `apps/` salvo `apps/api/package.json`, así que
  `IdentityScopeExclusionInventoryTest` sigue sin encontrar ningún `*.spec.ts`.
- **Riesgo para el cambio 13:** cuando exista `apps/admin-web/node_modules`, esa prueba recorrerá también
  las dependencias instaladas, que sí traen archivos `*.spec.js`. Debe excluir `node_modules` antes de
  que se cree la primera aplicación.
- `pnpm turbo run lint typecheck test build --filter=!@confia/api`: **7 de 7 tareas en verde**.
- **Diff del corte**, sin `pnpm-lock.yaml` (generado, 2 651 líneas) ni `openspec/`: **+389 −4** en 25
  archivos. Por debajo de 800; no hizo falta la división 3b1/3b2.
