# Corte: estado de cuenta del estudiante (ledger y charges)

Fase F3, corte 5 de `docs/cortes/ledger-core.md` («Propuesta de cortes de F3»), entregable 9 de `docs/09-roadmap-y-fases.md`.
Estado: **backend construido** (ver «Construido (backend)» al final); sin pantalla en esta pasada. Cierra el criterio de salida «el saldo de un
estudiante con 12 meses de historial simulada se reconstruye exactamente desde cero».

## Objetivo

Una vista **de solo lectura y derivada** de lo que un estudiante debe, construida con los asientos de su subcuenta por cobrar (INV-07) y
enriquecida con el cargo que produjo cada asiento: concepto, período y vencimiento. Ningún dato financiero se escribe en este corte.

## Fuera de alcance

Mora y «vencido» (no existen pagos todavía, así que no se muestra); trabajo nocturno de integridad, cierre de período y caché de saldo (el punto de
extensión queda como en `ledger-core`); PDF, impresión y exportación; pagos, ajustes y su pantalla; reverso de un cargo (corte 3b); pantalla del
estado de cuenta (siguiente pasada del corte); el portal de encargados (F8).

## Decisiones del propietario

1. **Ubicación.** El estado se compone en `charges`, que ya depende de `ledger`; `ledger` **no** conoce a `charges` (sin ciclo de módulos). Se
   amplía la API pública `ledger.application.api` con `LedgerStatementApi` (lectura de las líneas de la subcuenta de un estudiante por moneda y
   saldo a una fecha) y `charges` une `charges_charge` por `ledger_transaction_id`. Las líneas cuya transacción no es un cargo (pagos, ajustes,
   reversos futuros) muestran solo tipo y motivo.
2. **Signo.** La cuenta por cobrar es un activo: el débito aumenta lo que el estudiante debe. El saldo es **positivo** si debe y **negativo** si
   tiene saldo a favor.
3. **El saldo siempre se deriva** de los asientos en cada consulta; no se almacena ni se guarda en caché.
4. **Dinero solo como `{amount, currency}`** de texto, redondeado por el servidor a la escala menor de la moneda (`HALF_UP`). El **saldo
   corrido** de cada línea lo calcula la base de datos con una función de ventana; el navegador nunca suma ni redondea.
5. **Monedas.** Un estudiante puede tener subcuentas en varias monedas. Se eligió la opción más delgada y correcta: el parámetro `currency` es
   **opcional**. Sin él se devuelve una **sección por cada moneda que el estudiante tiene** (ninguna si no tiene cuenta); con él, solo esa sección
   (con ceros si no tiene subcuenta en ella).
6. **Paginación del lado del servidor** sobre las líneas de cada sección; el saldo inicial es el saldo derivado al día anterior a `from`; `from` y
   `to` acotan la **fecha efectiva**; el período por omisión son los últimos 12 meses (`to` = hoy en `America/Tegucigalpa`, `from` = 12 meses antes).
7. «Vencido» fuera de alcance. 8. Fuera de alcance lo listado arriba.

## Reglas

- **Línea** = un asiento de la subcuenta del estudiante (`ledger_entry` con `student_account_id`). Orden canónico y determinista: fecha efectiva,
  instante de registro, identificador de la transacción y número de línea (el mismo de `LedgerQueries.entries`), de modo que dos peticiones de la
  misma página devuelven lo mismo mientras no haya asientos nuevos.
- **Saldo inicial** = débitos menos créditos con fecha efectiva **anterior** a `from`. **Saldo final** = saldo inicial + neto del período =
  débitos menos créditos con fecha efectiva hasta `to` (igual a `LedgerQueries.balanceOf(to)`). **Saldo corrido** de una línea = saldo inicial +
  suma acumulada hasta esa línea, en el orden canónico; la última línea del período trae el saldo final.
- `debit` y `credit` están siempre presentes y uno es cero. Un reverso espeja los asientos del original: el cargo y su reverso suman cero.
- **Enriquecimiento:** `charge` (cargo, concepto con código y nombre, categoría, período `YYYY-MM`, inicio, fin, vencimiento y origen) solo cuando la
  transacción es el asiento de un cargo. El código y el nombre del concepto salen del directorio de `catalog` (se ven aunque el concepto se haya
  desactivado). Un reverso muestra `reversesTransactionId` y su motivo.
- **Consistencia.** Cada sección sale de dos sentencias: una de totales (saldo inicial, neto y conteo, una sola foto) y otra de la página con su
  saldo corrido (que incluye el saldo inicial dentro de la misma sentencia). Un asiento confirmado entre ambas puede distinguirlas en esa
  petición; la siguiente es exacta. El nivel de aislamiento es `READ COMMITTED`: ADR-0010 reserva `SERIALIZABLE` a dos operaciones.
- **Estudiante** de otra institución o inexistente: `404 resource-not-found` (RLS más el filtro por institución del directorio de estudiantes).
- **Auditoría.** Consultar el estado de cuenta es acceso a datos financieros de un menor: `docs/03-seguridad.md` y la skill de auditoría piden
  auditar el acceso a datos personales y, ante la duda, auditar. Cada petición exitosa escribe `charges.statement.viewed` (entidad
  `charges.statement`, identificador del estudiante) en la **misma transacción**, con la moneda, `from`, `to`, página y tamaño; nunca importes ni
  nombres. La petición corre por eso en una transacción de lectura y escritura (`READ COMMITTED`). Una petición rechazada (404, 400) no deja
  entrada. Ningún importe ni nombre va a los logs.
- **Sin migración.** Todo sale de las tablas de V30 y V32; no se agregan índices (los asientos ya se leen por subcuenta y transacción; si el
  volumen lo pide, lo decide `confia-database`).

## API (`/api/v1`)

| Ruta | Permiso | Notas |
|---|---|---|
| `GET /charges/students/{studentId}/statement?currency=&from=&to=&page=&size=` | `charges:read` | `size` 1 a 100 (50 por omisión), `page` desde 0; `from`/`to` `yyyy-MM-dd` |

Respuesta: `{studentId, studentCode, firstName, lastName, from, to, sections: [{currency, openingBalance, closingBalance, totalLines, page, size,
lines: [{transactionId, kind, effectiveOn, postedAt, lineNumber, reason?, reversesTransactionId?, debit, credit, balance, charge?: {chargeId,
paymentConceptId, conceptCode, conceptName, conceptCategory, periodLabel, periodStart, periodEnd, dueOn, origin}}]}]}`. `page` y `size`
aplican a las líneas de cada sección. Errores: `400 validation-failed` (fecha o moneda inválida, `from` posterior a `to`, página fuera de rango),
`403 forbidden` sin `charges:read`, `404 resource-not-found`. Ningún código de problema nuevo. Solo cambia el proceso administrativo.

El permiso `ledger:read` no se usa en este corte: la lectura pública de `ledger` es una API entre módulos, no una ruta, y el estado de cuenta es
una vista del cobro (`charges:read`). Las rutas `GET /ledger/students/...` previstas en `ledger-core` no se crean.

## Casos límite

Estudiante sin cuenta ni asientos (sin secciones; con `currency`, sección en ceros); saldo a una fecha anterior a todo y posterior a todo;
`from` igual a `to`; `from` posterior a `to`; orden por fecha efectiva con registro tardío (un asiento con fecha anterior registrado después);
saldo corrido exacto con muchas líneas; cargo con y sin enriquecimiento; cargo reversado (el reverso lo anula: saldo cero y los dos aparecen);
saldo a favor (negativo); dos monedas; límites `from`/`to` incluidos; estabilidad de la paginación; página más allá del final; tamaño 0 o mayor
que 100; moneda desconocida; 12 meses de cargos del motor de devengo que se reconstruyen desde cero; estudiante de otra institución (`404`);
`403` para un rol sin `charges:read`; ningún dato de otra institución en la respuesta ni en la bitácora.

## Criterio de aceptación

Con 12 cargos mensuales de un estudiante generados por el motor de devengo, el estado de cuenta devuelve el saldo final igual a débitos menos
créditos de la subcuenta y a `balanceOf` a la fecha final; cada saldo corrido es exacto; el cargo y su reverso dejan saldo cero; un estudiante de
otra institución responde `404`; sin `charges:read` responde `403`; cada consulta exitosa deja su entrada de auditoría.

## Construido (backend)

- **ledger**: la API pública `ledger.application.api` suma `LedgerStatementApi` (`currenciesOf` y `statement`, tipos planos: ni un tipo del dominio
  cruza la frontera); la implementa `LedgerApi` y la consulta `JooqLedgerRepository` (`studentCurrencies` y `statement`: totales con
  `SUM ... FILTER` en una sola sentencia y página con `SUM() OVER (ORDER BY fecha efectiva, instante de registro, transacción, línea ROWS UNBOUNDED
  PRECEDING)` más el saldo inicial como subconsulta escalar; `Money` solo por `MoneyColumns`; el límite de página es 100 y se valida también aquí).
  Solo lectura: el puerto del libro no gana ninguna operación de escritura.
- **catalog**: `PaymentConceptDirectory.labels` y `PaymentConceptLabel` (código y nombre, también de conceptos desactivados) para el único consumidor,
  `charges`.
- **charges**: `ViewStudentStatement` (verifica el estudiante por `StudentDirectory`, une `charges_charge` por `ledger_transaction_id` con
  `ChargeRepository.findByLedgerTransactions`, etiqueta el concepto y audita), `StatementView`, `StatementController` y `StatementResponse` (dinero por
  `AccrualMoney`), la ruta en `PermissionedEndpoints` y la entrada `charges.statement.viewed` en `ChargesAudit`. `ledger` no conoce a `charges`.
- **Sin migración.** Sin permiso nuevo (`charges:read`: todos los roles de personal lo tienen). Sin código de problema nuevo.
- **Contrato:** OpenAPI de administración con 1 operación nueva (79 a 80 con permiso) y 1 ruta (`getStudentStatement`) y 4 esquemas nuevos
  (`StudentStatement`, `StudentStatementSection`, `StudentStatementLine`, `StudentStatementCharge`); aditivo, 302 líneas añadidas y ninguna cambiada;
  `packages/contracts` regenerado con `pnpm generate`. El OpenAPI del portal no cambia.
- **Pruebas:** `ViewStudentStatementTest` (composición con puertos en memoria: enriquecimiento, línea sin cargo, secciones por moneda, período por
  omisión en `America/Tegucigalpa`, validación, 404, auditoría), `LedgerStatementIT` (PostgreSQL real: sin cuenta, saldo antes y después de los
  asientos, `from`/`to` incluidos, asiento tardío con fecha anterior, saldo corrido exacto con más de 100 líneas con fracciones y pagos, paginación
  estable y página más allá del final, reverso que anula, saldo a favor negativo, dos monedas, otro estudiante y otra institución), `ViewStudentStatementIT`
  (los 12 cargos mensuales del motor de devengo reconstruyen el saldo desde cero y el saldo final iguala `balanceOf` y la suma de débitos menos créditos;
  período por omisión; reverso y pago sin cargo; dos monedas; sin cuenta; otra institución; bitácora) y `StatementEndpointsIT` (proceso administrativo
  real: forma de la respuesta, dinero como texto, páginas, filtros, 400, 404, 401, rol con lectura, bitácora). Se actualizaron las listas fijadas de
  `OpenApiContractSnapshotTest`, `PublicRouteAllowListTest`, `CurrentSessionEndpointIT` y `PermissionedEndpointsTest`.
- **Decisiones tomadas en la construcción:** (1) `currency` opcional con secciones por moneda (decisión 5); (2) la petición corre en una transacción de
  lectura y escritura `READ COMMITTED` porque audita cada acceso; (3) cada página pedida es un acceso y deja su entrada; (4) la primera línea de una
  página trae el saldo corrido de todo el historial anterior, no cero; (5) el saldo corrido de un reverso y de un pago usa el mismo signo que un
  cargo; (6) el encabezado lleva código y nombre del estudiante porque la pantalla los necesita (los publica `StudentDirectory`, nunca el documento ni
  la fecha de nacimiento) y `StatementView.toString` no los imprime.
- **Límite conocido:** ningún rol de personal carece de `charges:read` (los seis lo tienen), así que el `403` de la ruta no se puede probar con un rol
  real; lo cubren la entrada de `PermissionedEndpoints` (probada) y la guarda del decididor de permisos.
- **Pendiente:** pantalla del estado de cuenta (siguiente pasada del corte), índice de lectura por subcuenta y fecha efectiva si el volumen lo pide
  (`confia-database`), trabajo nocturno de integridad, cierre de período y decisión sobre la caché (siguen fuera de este corte), y la línea de avance
  en `docs/09-roadmap-y-fases.md` al cerrar el PR.

## Construido (frontend)

- **Pantalla** `/charges/statement` («Estado de cuenta», grupo «Finanzas», junto a «Generación de cargos») en `features/charges`. El estado vive en la
  URL (`studentId`, `currency`, `from`, `to`, `page`; tamaño de página fijo de 25 líneas), así que una vista se comparte y otras pantallas pueden
  enlazarla con `?studentId=`. La ruta compartida vive en `shared/navigation/statement-paths.ts` (`STATEMENT_PATH`, `studentStatementPath`) para que
  `students` enlace sin importar `charges`.
- **Selector de estudiante** con búsqueda en el servidor (`shared/student-lookups/use-student-search.ts`, la lista de estudiantes con `q`, todos los
  estados; combobox ARIA con flechas y Enter). Una vez elegido muestra código y nombre tomados del encabezado del estado de cuenta.
- **Período** con «Desde» y «Hasta» (si se dejan vacíos, el servidor aplica los últimos 12 meses). Si `from > to` se avisa en el campo «Hasta» y
  **no se consulta**. **Moneda**: el selector aparece solo si el estudiante tiene más de una; las monedas se recuerdan de la última lectura sin
  filtrar, para que el selector sobreviva al filtro.
- **Por moneda**: tarjetas «Saldo inicial» y «Saldo final» con el texto «Saldo a favor» junto a la cifra cuando es negativa (más una frase explícita
  debajo; nunca solo signo o color), y tabla densa con fecha, descripción (concepto y período para un cargo; tipo y motivo para lo demás, con
  insignia «Cargo»/«Abono»), vencimiento, columnas Cargo y Abono (un guion con texto para lectores de pantalla cuando es cero), saldo corrido y
  paginación del servidor. El navegador solo mira el signo del texto (`balanceSideOf`); no suma, resta ni redondea.
- **Estados**: sin estudiante (guía), cargando (esqueleto), vacío (estudiante sin cuenta), período inválido, error con reintento e identificador de
  soporte, 404 (estudiante de otra institución) y 403 sin reintento, éxito. La invalidación: `statementKeys.all` se invalida al encolar una corrida
  de devengo.
- **Estudiantes**: enlace «Estado de cuenta» en cada fila del registro, por la ruta compartida.
- **Simulador** (`e2e/mock-api/statement-*.ts`): cinco estudiantes sembrados (12 meses con 2 pagos; saldo a favor; sin movimientos; dos monedas;
  60 líneas para paginar), saldos calculados en el simulador desde centavos enteros, período por omisión, validación, 404 (`FOREIGN_STUDENT_ID`),
  y los presets `statement-empty`, `statement-forbidden` y `statement-server-error`. Pruebas del modelo y del servidor.
- **Pruebas**: modelo puro, tarjetas y tabla, selector, vista y contenedor con MSW (guía, enlace profundo, página, período inválido, vacío, dos
  monedas, 404, 403, 500 con reintento, búsqueda y elección). Pasaron `tsc --noEmit`, eslint sobre los archivos tocados y las pruebas citadas.
- **Límite del contrato:** sin `currency`, un estudiante sin cuenta recibe cero secciones, así que la pantalla explica que no tiene movimientos pero
  no puede mostrar saldos en cero (el navegador no inventa cifras). Mostrarlos exigiría que el servidor devolviera una sección en cero por la
  moneda de la institución.
- **Pendiente:** recorridos de Playwright y estados de la matriz de accesibilidad de la pantalla (no se corrieron en esta pasada; las puertas de axe
  de `@confia/ui` y de rutas siguen sin cubrir esta ruta), `dependency-cruiser` sobre el árbol completo (no disponible localmente en esta pasada),
  revisión visual con el simulador reiniciado, y la línea de avance en `docs/09-roadmap-y-fases.md` al cerrar el PR.

