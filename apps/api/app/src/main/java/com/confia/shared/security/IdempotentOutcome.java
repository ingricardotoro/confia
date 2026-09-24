package com.confia.shared.security;

/**
 * What an idempotent operation actually did, distinguishing a real execution from a replayed
 * response (design.md, section 6.1 and decision 9; specs/build-integrity/spec.md, requirement
 * "Respuesta reproducible ante clave completada, sin reejecutar el caso de uso"). Lets a future
 * caller such as {@code IdempotentExecutor} (PR C2b) produce the {@code Idempotent-Replay} header
 * without querying anything again — the distinction is already carried in the type, not
 * reconstructed from the response body.
 *
 * <p>No type from PR C2a-1 consumes this interface yet; {@code IdempotentExecutorIT} (PR C2b) is
 * where {@link Executed} and {@link Replayed} are first exercised.
 */
public sealed interface IdempotentOutcome {

    IdempotentResponse response();

    /** The use case actually ran and its result is what {@code response()} carries. */
    record Executed(IdempotentResponse response) implements IdempotentOutcome { }

    /** The stored response from a previous execution, returned without running the use case again. */
    record Replayed(IdempotentResponse response) implements IdempotentOutcome { }
}
