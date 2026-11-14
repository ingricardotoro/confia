# Procedimiento: Simulacro de restauración de respaldo

- **Cadencia:** mensual, obligatoria. Una vez al año, en un proveedor distinto de AWS (simulacro de salida, ADR-0014).
- **Responsable de ejecutarlo:** el desarrollador responsable del sistema.
- **Quienes firman el acta:** el desarrollador responsable y la dirección de la institución.
- **Procedimiento técnico que se ensaya:** [restauracion-de-respaldo.md](restauracion-de-respaldo.md). Este documento no lo reemplaza: dice cómo ensayarlo y qué evidencia dejar.

Un respaldo que nunca se restauró es una suposición. El simulacro existe para probar que los
respaldos sirven, no solo que existen.

---

## Reglas

1. **El simulacro nunca toca la base de producción.** Ni para leer, ni para comparar. Solo se descarga un respaldo ya almacenado.
2. **Se restaura en un entorno limpio y desechable**: una instancia de PostgreSQL 18 recién creada, sin datos previos y sin acceso desde Internet.
3. **Se usa un respaldo real** del almacenamiento fuera de sitio, no uno generado para la ocasión. Se elige el más reciente de `nightly/`, y una vez al trimestre uno de `monthly/` para probar la retención.
4. **La llave de descifrado sale de su custodia habitual**, la misma que se usaría en una emergencia. Si no se puede acceder a ella, el simulacro **falla**, y eso es un hallazgo, no un contratiempo.
5. **No se copian datos personales fuera del entorno desechable.** Las consultas de verificación devuelven conteos y diferencias, no filas con datos de estudiantes (`docs/08-datos-privacidad-y-retencion.md`).
6. **Al terminar se destruye todo**: la base restaurada, el archivo descifrado y cualquier copia local. La evidencia es el acta, no los datos.

---

## Antes de empezar

- [ ] Fecha y hora de inicio anotadas (UTC). El cronómetro del objetivo de tiempo de recuperación (4 horas) arranca aquí.
- [ ] Acceso al almacenamiento fuera de sitio (`OFFSITE_S3_ENDPOINT`, `OFFSITE_S3_BUCKET`) con credenciales de **solo lectura**.
- [ ] Acceso a la llave privada de descifrado en su custodia (nunca en el repositorio ni en el servidor de aplicación).
- [ ] Herramientas disponibles: `aws` (cliente S3), `age`, `sha256sum`, `pg_restore` y `psql` de la versión 18.
- [ ] Un entorno limpio: contenedor o instancia de PostgreSQL 18 vacía.
- [ ] Plantilla de acta copiada a `actas/AAAA-MM-acta-simulacro-restauracion.md`, a partir de [actas/PLANTILLA.md](actas/PLANTILLA.md).

---

## Pasos

Cada paso termina con un dato que se anota en el acta.

### 1. Elegir el respaldo

```bash
aws --endpoint-url "${OFFSITE_S3_ENDPOINT}" s3 ls "s3://${OFFSITE_S3_BUCKET}/nightly/" | tail -20
```

Anota la marca de tiempo del respaldo elegido. Su antigüedad respecto al inicio del simulacro es el **punto de recuperación observado**.

### 2. Descargar y comprobar la integridad

```bash
aws --endpoint-url "${OFFSITE_S3_ENDPOINT}" s3 cp \
  "s3://${OFFSITE_S3_BUCKET}/nightly/<marca-de-tiempo>/" ./drill --recursive

cd drill && sha256sum --check confia-nightly-<marca-de-tiempo>.dump.age.sha256
```

Debe decir `OK`. Si no, el respaldo está dañado: se anota como fallo y se repite con el respaldo anterior para saber desde cuándo ocurre.

### 3. Descifrar

```bash
age --decrypt --identity <ruta-a-la-llave> \
  "confia-nightly-<marca-de-tiempo>.dump.age" > "confia-nightly-<marca-de-tiempo>.dump"
```

Anota quién custodiaba la llave y cuánto tardó en conseguirla.

### 4. Restaurar en el entorno limpio

```bash
createdb confia_restore_drill
pg_restore --dbname=confia_restore_drill --exit-on-error "confia-nightly-<marca-de-tiempo>.dump"
```

Anota la duración. Cualquier error de `pg_restore` es un hallazgo.

### 5. Ejecutar las cinco verificaciones

Son las del apartado «Verificación» de [restauracion-de-respaldo.md](restauracion-de-respaldo.md), apuntando a `confia_restore_drill`:

1. La base responde y la fecha del último movimiento es coherente con el respaldo.
2. Los conteos son razonables frente a los últimos valores conocidos.
3. El libro mayor cuadra: **cero** transacciones con diferencia.
4. La cadena de auditoría está íntegra: **cero** eslabones rotos.
5. Los correlativos fiscales son coherentes con el contador de cada rango CAI.

Pega en el acta el **resultado numérico** de cada una, no los datos.

### 6. Comprobar que la aplicación arranca contra la copia

Levanta una instancia de la aplicación en el entorno desechable apuntando a `confia_restore_drill` y comprueba que arranca y que Flyway no encuentra un historial inconsistente. Anota la versión de la aplicación y la última migración aplicada.

### 7. Medir y cerrar

- Hora de fin. **Tiempo total** = fin − inicio, comparado con el objetivo de 4 horas.
- **Punto de recuperación observado** = inicio del simulacro − marca de tiempo del respaldo. Para la ruta fuera de AWS el límite aceptado es el del último respaldo nocturno (ADR-0014).

### 8. Destruir

```bash
dropdb confia_restore_drill
shred -u confia-nightly-*.dump confia-nightly-*.dump.age   # o el borrado seguro equivalente del sistema
```

Confirma en el acta que se destruyó todo. El simulacro no termina sin este paso.

---

## Criterio de aprobación

El simulacro **aprueba** solo si se cumple todo:

- [ ] La integridad del archivo (`sha256`) coincide.
- [ ] La llave de descifrado se obtuvo desde su custodia habitual.
- [ ] `pg_restore` terminó sin errores.
- [ ] Las cinco verificaciones pasan.
- [ ] La aplicación arranca contra la copia.
- [ ] El tiempo total fue menor o igual a 4 horas.
- [ ] Todo lo desechable se destruyó.

Si algo falla, el simulacro **no aprueba** y el acta lo dice así. Cada fallo genera una acción correctiva con responsable y fecha, y el simulacro se repite cuando esté resuelta. Un acta honesta de un simulacro fallido vale más que una aprobada sin evidencia.

---

## Después

1. Completa y firma el acta; guárdala en `docs/runbooks/actas/`, sin datos personales.
2. Actualiza la línea «Última prueba de este procedimiento» de [restauracion-de-respaldo.md](restauracion-de-respaldo.md) con la fecha y el resultado.
3. Si es el **primer** simulacro aprobado, marca el criterio de salida de la fase 0 en `docs/09-roadmap-y-fases.md` («Un respaldo se restaura en un entorno limpio y el acta de simulacro está firmada»), con una nota fechada que enlace al acta. Ese cambio también obliga a actualizar `DocumentationNotesTest` del backend, que hoy exige el criterio abierto.
4. Registra las acciones correctivas pendientes.

---

## Nota para la fase 0

Si todavía no hay datos de producción, el primer simulacro puede hacerse sobre un respaldo de la base de preproducción. El acta debe decir **de qué entorno** venía el respaldo. Un simulacro sobre preproducción prueba el procedimiento y la llave, pero no el volumen ni la forma real de los datos: se repite con el primer respaldo de producción.

`infra/scripts/backup.sh` aún no existe en el repositorio (solo está descrito en `docs/05-infraestructura-y-despliegue.md`, sección 11). Antes del primer simulacro hace falta que exista al menos un respaldo real en el almacenamiento fuera de sitio.
