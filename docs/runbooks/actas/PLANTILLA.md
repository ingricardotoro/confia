# Acta de simulacro de restauración de respaldo

> Copia este archivo a `AAAA-MM-acta-simulacro-restauracion.md` y complétalo. No pegues datos personales: solo conteos, hashes y resultados.

| Campo | Valor |
|---|---|
| Fecha del simulacro (UTC) | |
| Hora de inicio / fin (UTC) | |
| Ejecutó | |
| Procedimiento | `docs/runbooks/simulacro-de-restauracion.md` |
| Tipo | ☐ Mensual   ☐ Anual de salida (proveedor distinto de AWS) |
| Entorno de origen del respaldo | ☐ Producción   ☐ Preproducción |
| Entorno de restauración | (descripción, versión de PostgreSQL) |

## Respaldo usado

| Campo | Valor |
|---|---|
| Ruta en el almacenamiento fuera de sitio | |
| Marca de tiempo del respaldo | |
| Integridad `sha256` | ☐ OK   ☐ Falla |
| Custodio de la llave / tiempo en conseguirla | |

## Mediciones

| Medida | Objetivo | Observado |
|---|---|---|
| Tiempo total de restauración y verificación | ≤ 4 horas | |
| Punto de recuperación (antigüedad del respaldo) | ≤ último respaldo nocturno | |
| Duración de `pg_restore` | informativo | |

## Verificaciones

| # | Verificación | Resultado esperado | Resultado observado | ¿Pasa? |
|---|---|---|---|---|
| 1 | La base responde y la fecha es coherente | último movimiento ≤ respaldo | | ☐ |
| 2 | Conteos razonables | sin caída brusca | | ☐ |
| 3 | El libro mayor cuadra | 0 transacciones con diferencia | | ☐ |
| 4 | Cadena de auditoría íntegra | 0 eslabones rotos | | ☐ |
| 5 | Correlativos fiscales coherentes | contador ≥ último emitido | | ☐ |
| 6 | La aplicación arranca contra la copia | arranca, migraciones coherentes | | ☐ |

## Destrucción

- [ ] Base restaurada eliminada
- [ ] Archivos descifrados y descargados eliminados

## Resultado

☐ **Aprobado**   ☐ **No aprobado**

## Hallazgos y acciones correctivas

| Hallazgo | Acción | Responsable | Fecha límite |
|---|---|---|---|
| | | | |

## Firmas

| Rol | Nombre | Firma | Fecha |
|---|---|---|---|
| Desarrollador responsable | | | |
| Dirección de la institución | | | |
