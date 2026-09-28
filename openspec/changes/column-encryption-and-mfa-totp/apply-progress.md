# Progreso de aplicación: `column-encryption-and-mfa-totp`

- **Corte en curso:** C1
- **Entorno:** JDK 25 (Temurin 25.0.3+9), Maven 3.9.16, Docker disponible.
  El `JAVA_HOME` del sistema apunta al **JDK 21**, así que toda invocación de Maven exporta
  `JAVA_HOME` al 25 en la propia orden. `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` es
  un apaño local del entorno y **nunca se compromete**.

---

## Tarea 1.1 — Sondas S1 y S5

Ejecutadas por el orquestador antes de delegar la implementación, siguiendo el precedente de todos los
cortes anteriores. **Sin evidencia de ROJO propia:** son sondas de comportamiento de plataforma y de
motor, no pruebas del delta.

### S1 — ¿`Cipher` de AES-GCM concatena la etiqueta de 128 bits a la salida de `doFinal`?

**PASA, y responde más de lo que se preguntaba.** Programa mínimo fuera del árbol, con llave de 32
bytes, IV de 12 y datos adicionales autenticados con la forma `tabla|columna|id_de_fila` que exige
`docs/03-seguridad.md` §7.3:

```
proveedor            = SunJCE
plaintext.length     = 22
doFinal.length       = 38
diferencia           = 16 bytes
etiqueta concatenada = true
descifra con su AAD  = true
AAD ajeno rechazado  = AEADBadTagException
```

**Tres conclusiones, y las tres importan para el diseño del códec:**

1. **La etiqueta va concatenada al final.** La diferencia es exactamente 16 bytes, los 128 bits de la
   etiqueta. El códec del formato de cinco partes **debe partir los últimos 16 bytes** de la salida de
   `doFinal` para separar `<ciphertext_base64>` de `<tag_base64>`, y volver a concatenarlos antes de
   descifrar.
2. **El proveedor es `SunJCE`, del propio JDK.** Confirma la decisión de la propuesta de implementar el
   cifrado **sin dependencia nueva**, a diferencia de Argon2id, que necesitó Bouncy Castle porque el
   codificador de Spring no expone `withSecret(...)`.
3. **Los datos adicionales autenticados atan de verdad, y se sabe cómo falla.** Descifrar con el AAD de
   otra fila lanza **`javax.crypto.AEADBadTagException`**. El delta tiene un escenario que exige
   precisamente ese rechazo, y ahora se conoce **el tipo exacto de excepción** que la prueba debe
   esperar, en vez de afirmar un fallo genérico.

### S5 — ¿PostgreSQL 18 infiere el índice parcial como destino de `ON CONFLICT`?

**PASA.** Contra un `postgres:18-alpine` desechable, fuera del árbol, con la tabla y el índice parcial
de la decisión 3 del diseño:

```sql
create unique index dek_one_active_per_institution
  on dek (institution_id) where status = 'active';

insert into dek (...) values (..., 'active', ...)
  on conflict (institution_id) where status = 'active' do nothing;
```

Ejecutada dos veces con el mismo `institution_id`:

```
primera:  INSERT 0 1
segunda:  INSERT 0 0
filas activas al final: 1
```

**La creación perezosa de la primera llave de datos por institución es segura bajo concurrencia con
una sola sentencia**, sin necesidad de un `SELECT ... FOR UPDATE` previo ni de un respaldo. Es el mismo
tipo de ahorro que la sonda S3 del cambio anterior consiguió para el reclamo del retroceso.

### S2 — pendiente, y no puede ejecutarse todavía

La sonda de los nombres que jOOQ genera para las cuatro tablas nuevas **exige que `V6` exista**, así
que pertenece a la tarea 1.2, tal como el diseño la asigna. No se adelanta.
