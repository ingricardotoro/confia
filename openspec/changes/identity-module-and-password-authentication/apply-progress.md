# Progreso de aplicación: `identity-module-and-password-authentication`

- **Corte en curso:** PR C1
- **Entorno:** JDK 25 (Temurin 25.0.3+9), Maven 3.9.16, Docker disponible.
  `JAVA_HOME` del sistema apunta al **JDK 21**, así que toda invocación de Maven se hace exportando
  `JAVA_HOME` al JDK 25 en la propia orden. `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`
  es un apaño local del entorno y **nunca se compromete** (`design.md` §13).

---

## Tarea 1.1 — Sondas S1 y S1b

Ejecutadas por el orquestador antes de delegar la implementación, siguiendo el precedente de los
cortes anteriores. **Sin evidencia de ROJO propia**: son sondas de comportamiento de herramienta, no
pruebas del delta.

### S1 — ¿`ApplicationModules.verify()` rechaza que `identity.application` dependa de `TransactionRunner`?

**Resultado: PASA. Se confirma lo que el diseño predijo.**

Procedimiento: se creó temporalmente
`apps/api/app/src/main/java/com/confia/identity/application/ProbeS1.java`, una clase de producción
mínima que importa `com.confia.shared.security.TransactionRunner`, y se ejecutó
`./mvnw -B -pl app test -Dtest=SpringModulithVerificationTest` desde `apps/api`.

`SpringModulithVerificationTest` falló, en su prueba de producción
`productionModulesHaveNoViolationYet`, con este mensaje exacto:

```
org.springframework.modulith.core.Violations: - Module 'identity' depends on non-exposed type
com.confia.shared.security.TransactionRunner within module 'shared'!
```

La otra prueba de la clase, la del fixture, siguió pasando: 2 ejecutadas, 1 fallo.

**Consecuencia:** la decisión 12 del diseño es necesaria, no precautoria. Sin `@NamedInterface` sobre
`shared/security`, el módulo `identity` no puede consumir `TransactionRunner` y la construcción
rompe. El fixture temporal se eliminó y el árbol quedó limpio, verificado con `git status --short`.

### S1b — ¿Dónde vive `@NamedInterface` en Spring Modulith 2.1.1?

**Resultado: RESUELTA, y corrige un supuesto de la lista de tareas.**

`./mvnw -B -pl app dependency:tree -Dincludes=org.springframework.modulith` resuelve hoy:

```
org.springframework.modulith:spring-modulith-core:jar:2.1.1:test
 \- org.springframework.modulith:spring-modulith-api:jar:2.1.1:test
```

Inspeccionando los dos jar aparecen **dos tipos distintos con el mismo nombre simple**, y solo uno
es la anotación:

| Tipo | Artefacto | Qué es |
|---|---|---|
| `org.springframework.modulith.NamedInterface` | **`spring-modulith-api`** | **La anotación.** `javap` la muestra como `public interface ... extends java.lang.annotation.Annotation`, con `value()`, `name()` y `propagate()` |
| `org.springframework.modulith.core.NamedInterface` | `spring-modulith-core` | Una clase del modelo, `implements Iterable<com.tngtech.archunit.core.domain.JavaClass>`. No es una anotación |

**Corrección a la tarea 1.2.** La lista de tareas dice «declarar las anotaciones de Spring Modulith
en alcance de **compilación** … (hoy `spring-modulith-core` es de alcance `test`)», lo que lleva a
promover `spring-modulith-core`. **Eso sería un defecto:** `spring-modulith-core` arrastra ArchUnit
(`com.tngtech.archunit`), que es una biblioteca **solo de pruebas**, y promoverlo la pondría en el
camino de clases de producción.

Lo que debe declararse en alcance de compilación es **`spring-modulith-api`**, que es donde vive la
anotación y no arrastra ArchUnit. `spring-modulith-core` se queda en alcance `test`, que es donde
`SpringModulithVerificationTest` lo usa.

Queda pendiente para la tarea 1.2 comprobar, con la sonda **S6**, que `dependencyConvergence` del
enforcer sigue en verde tras declarar `spring-modulith-api` en alcance de compilación.
