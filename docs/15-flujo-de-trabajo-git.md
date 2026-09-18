# Flujo de trabajo con Git

> Convenciones de control de versiones de CONFIA. Con un solo desarrollador, el historial de Git es
> la única bitácora de por qué el sistema es como es. Se cuida en consecuencia.

---

## 1. Estrategia de ramas

Alineada al ciclo dirigido por especificaciones. Una rama por cambio, no por tarea.

```
main                  rama principal, protegida, siempre desplegable
 ├── change/<id>       una por cambio SDD
 └── hotfix/<slug>     correccion urgente en produccion
```

### Reglas

| Regla | Razón |
|---|---|
| `main` está protegida y no admite empuje directo | Ni siquiera con un solo desarrollador. La protección es contra los descuidos propios |
| Una rama por cambio SDD, nombrada con su identificador | La rama, la especificación y el pull request comparten identidad |
| La rama se crea desde `main` actualizada | Evita fusiones enredadas |
| La rama vive **días, no semanas** | Una rama de tres semanas es un conflicto de fusión esperando |
| Se borra tras fusionar | El historial queda en `main` |

### Nombres

```
change/f0-identity-foundations
change/f4-cash-session-close
change/f5-credit-notes
hotfix/cai-range-validation
```

En inglés, en minúsculas, con guiones, y prefijadas con la fase del roadmap cuando aplique.

---

## 2. Commits convencionales

```
<tipo>(<alcance>): <asunto>

<cuerpo opcional>

<pie opcional>
```

### Tipos

| Tipo | Cuándo | Ejemplo de alcance |
|---|---|---|
| `feat` | Funcionalidad nueva visible para el usuario | `payments`, `invoicing` |
| `fix` | Corrección de un defecto | `ledger`, `cashbox` |
| `refactor` | Cambio interno sin alterar comportamiento | `domain` |
| `perf` | Mejora de rendimiento | `reporting` |
| `test` | Pruebas nuevas o corregidas | `payments` |
| `docs` | Documentación, incluidos ADR y especificaciones | `adr`, `specs` |
| `build` | Dependencias, monorepo, Docker | `deps`, `docker` |
| `ci` | Flujos de integración continua | |
| `chore` | Mantenimiento sin efecto en producción | |
| `revert` | Reversión de un commit anterior | |

**El alcance es el módulo de negocio**, no la capa técnica. `feat(payments)`, nunca
`feat(controllers)`. Si el alcance no es un módulo del sistema, probablemente el commit toca
demasiadas cosas.

### Reglas del asunto

- Imperativo presente en inglés: `add`, no `added` ni `adds`.
- Máximo 72 caracteres.
- Sin punto final.
- Describe **qué cambia el commit**, no qué archivos tocó.

### Cuándo hace falta cuerpo

Siempre que la respuesta a "por qué" no sea evidente:

- Corrección de un defecto: qué fallaba y por qué.
- Decisión no obvia: por qué así y no de la otra forma.
- Cambio de esquema: qué implica y cómo se revierte.
- Cualquier cosa con implicación fiscal o de seguridad.

### Pie

```
Refs: change/f4-cash-session-close
Closes: #42
BREAKING CHANGE: el endpoint de pagos ahora exige Idempotency-Key
```

### Regla explícita sobre atribución

**Nunca se agrega atribución de herramientas de inteligencia artificial a los commits.** Ni
`Co-Authored-By` de un asistente, ni menciones en el cuerpo, ni etiquetas generadas. El historial
registra decisiones del proyecto, no qué herramienta se usó para escribirlas.

### Ejemplos

Correctos:

```
feat(payments): add idempotency key handling to payment registration

Prevents duplicate charges when the cashier double-clicks or when the
payment gateway retries a webhook. The key is generated when the form
opens, not when it is submitted, so a resubmission reuses it.

Refs: change/f4-payment-registration
```

```
fix(ledger): defer balance constraint trigger until transaction commit

The trigger fired on the first entry insert, when the transaction is not
yet balanced by definition, so every valid posting failed. Made the
constraint DEFERRABLE INITIALLY DEFERRED.

Refs: change/f3-ledger-core
```

```
docs(adr): accept ADR-0007 on double-entry ledger
```

Incorrectos, con su motivo:

```
fix: bug                                   -> no dice que fallaba
update payment service                     -> sin tipo, y describe archivos
feat(controllers): add endpoint            -> alcance tecnico, no de negocio
feat(payments): agregar registro de pago   -> los commits van en ingles
fix(ledger): corrections                    -> plural vago, sin contenido
feat: add payments, fix ledger, update docs -> tres commits en uno
```

---

## 3. Tamaño de los pull requests

**Máximo ochocientas líneas de cambio efectivo.** Por encima de eso, la calidad de la revisión cae
de forma abrupta, y con un solo desarrollador la revisión ya es el eslabón más débil. El propietario
del producto fijó este presupuesto el 2026-09-18 (antes eran cuatrocientas).

### Cómo dividir uno grande

Se divide en unidades **revisables y coherentes**, encadenadas. Cada una debe compilar, pasar sus
pruebas y tener sentido por sí sola.

Orden habitual para una capacidad nueva:

1. Esquema y migración
2. Dominio: entidades, objetos de valor e invariantes, con sus pruebas unitarias
3. Aplicación: casos de uso y puertos, con pruebas
4. Infraestructura: repositorios y adaptadores, con pruebas de integración
5. Interfaz: controladores y DTO, con pruebas de contrato
6. Frontend: pantalla y sus pruebas

Cada pull request apunta al anterior, no a `main`. Se fusionan en orden.

**Lo que nunca se separa del código:** sus pruebas y la actualización de la documentación que el
cambio invalida. Un pull request de código sin pruebas rompe la unidad revisable, porque el revisor
no puede juzgar si el código es correcto.

---

## 4. Revisión con un solo desarrollador

Este es el punto débil estructural del proyecto y conviene ser honesto sobre él.

### El procedimiento

Antes de fusionar, se ejecuta `/confia-revision`, que orquesta:

1. `confia-code-reviewer` contra las reglas no negociables y la definición de terminado
2. `confia-security-auditor` con la lista de verificación de seguridad
3. `confia-fiscal-compliance` si el cambio toca facturación
4. Verificación de reglas de dependencia entre capas
5. Verificación de umbrales de cobertura
6. Trazabilidad contra los escenarios de la especificación

Un hallazgo bloqueante detiene la fusión.

### La advertencia

**La revisión asistida no sustituye una revisión humana externa.** Un agente revisa contra reglas
declaradas; no cuestiona si las reglas son correctas, no conoce el contexto de la institución, y no
sospecha de lo que nadie anticipó.

Para cambios de alto riesgo se recomienda revisión humana externa aunque cueste organizarla:

| Área | Riesgo |
|---|---|
| Autenticación y autorización | Una falla expone todo el sistema |
| Emisión y anulación de documentos fiscales | Consecuencias legales |
| Motor de cálculo de mora e impuestos | Reclamos de familias, ajustes masivos |
| Migración de saldos iniciales | Corrompe el punto de partida de todo el sistema |
| Políticas de seguridad a nivel de fila | Una falla expone datos de menores |

Esta limitación está registrada en `docs/11-riesgos.md` como parte del factor de bus.

---

## 5. Migraciones de base de datos

| Regla | Razón |
|---|---|
| Una migración por cambio | Facilita revisión y reversión |
| **Nunca se edita una migración ya aplicada en producción** | Los entornos quedan divergentes de forma irreparable |
| Los cambios destructivos van en dos pasos | Permite revertir el despliegue sin perder datos |
| Toda migración lleva su reversión escrita y probada | Una reversión no probada no existe |
| Respaldo verificado antes de aplicar en producción | Es la última red |
| Las migraciones se revisan con atención especial | Es el cambio menos reversible del sistema |

### Cambio destructivo en dos pasos

Ejemplo: renombrar una columna.

**Despliegue uno.** Agregar la columna nueva, escribir en ambas, leer de la vieja. Migrar los datos.
**Despliegue dos**, tras confirmar que el uno es estable: leer de la nueva. **Despliegue tres**,
días después: eliminar la vieja.

Parece excesivo hasta la primera vez que hay que revertir un despliegue a las once de la noche.

---

## 6. Etiquetado de versiones

Versionado semántico, con las etiquetas ancladas a las fases del roadmap.

| Componente | Se incrementa cuando |
|---|---|
| Mayor | Ruptura del contrato de la API, que afectaría a la app móvil |
| Menor | Funcionalidad nueva compatible. Típicamente al cerrar una fase |
| Parche | Correcciones compatibles |

```
v0.1.0   F0 Fundaciones
v0.2.0   F1 Nucleo academico
...
v0.6.0   F5 Facturacion fiscal
v1.0.0   Primera version en produccion con la institucion operando
```

Antes de `v1.0.0` el sistema no está en producción con dinero real. Ese es el significado de la
primera versión mayor, no una fecha del calendario.

Cada etiqueta lleva notas de versión generadas desde los commits convencionales, con la sección de
migraciones destacada.

---

## 7. Correcciones urgentes en producción

Solo para: sistema caído, imposibilidad de cobrar o facturar, fuga de datos, o corrupción activa de
datos financieros. Un defecto molesto pero no bloqueante **no** es una corrección urgente.

### Procedimiento

1. Rama `hotfix/<slug>` desde la etiqueta de producción, **no desde `main`**.
2. El cambio mínimo que resuelve el problema. Nada de mejoras aprovechando el viaje.
3. Prueba que reproduce el defecto y falla antes de la corrección. **No se salta.** Es precisamente
   bajo presión cuando se cometen los errores que hacen falta las pruebas.
4. Revisión asistida abreviada: reglas no negociables y seguridad. Las demás verificaciones se
   completan después.
5. Respaldo verificado antes de desplegar si hay migración.
6. Despliegue con reversión preparada.
7. **Retro-aplicar a `main` el mismo día.** Una corrección que solo vive en producción se pierde en
   el despliegue siguiente y el defecto reaparece.
8. Etiqueta de parche.
9. Registro del incidente y actualización de la especificación afectada.

El paso siete es el que más se olvida y el que produce la reaparición de defectos ya corregidos.

---

## 8. Qué nunca se compromete al repositorio

| Nunca | Por qué |
|---|---|
| Archivos `.env` con valores reales | Secretos expuestos de forma permanente en el historial |
| Llaves privadas, certificados, tokens | Igual |
| Volcados de base de datos | Contienen datos personales de menores |
| **Archivos con datos reales de estudiantes o encargados** | Violación de protección de datos. Incluye hojas de cálculo de la institución para migración |
| Respaldos | Tamaño y contenido sensible |
| PDF de facturas emitidas | Datos fiscales y personales |
| Capturas de pantalla con datos reales | Fuente común de filtración accidental |
| Artefactos de compilación | Se generan |

El caso de las hojas de cálculo de migración merece énfasis: llegan por correo desde la institución,
se dejan en la carpeta del proyecto para trabajar con ellas, y terminan comprometidas sin que nadie
lo note. El archivo `.gitignore` las excluye por extensión, y el escaneo de secretos en integración
continua es la segunda red.

Si un secreto o un dato personal llega al repositorio: se considera comprometido de forma
permanente. Se rota el secreto de inmediato, y solo después se limpia el historial. Limpiar sin
rotar no sirve de nada, porque el historial ya se replicó.

---

## 9. Firma de commits

**Todo commit va firmado**, con GPG o con SSH.

En un sistema financiero auditable, la pregunta de quién autorizó un cambio tiene que tener respuesta
verificable. Sin firma, el autor de un commit es un campo de texto que cualquiera puede escribir: no
prueba nada.

```bash
git config --global user.signingkey <llave>
git config --global commit.gpgsign true
git config --global tag.gpgsign true
```

Con SSH:

```bash
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/id_ed25519.pub
git config --global commit.gpgsign true
```

La rama `main` se configura para exigir commits firmados. Es también un control de seguridad de la
cadena de suministro: si alguien compromete las credenciales del repositorio, no puede introducir
commits firmados sin la llave.

---

## 10. Documentos relacionados

- `docs/13-metodologia-sdd.md`
- `docs/11-riesgos.md`
- `docs/05-infraestructura-y-despliegue.md`
- `docs/12-agentes-y-herramientas.md`
- `.claude/commands/confia-revision.md`
