package com.lynx.idempotency;

/**
 * Signal that the storage layer's UNIQUE constraint rejected a write —
 * i.e. the database has judged this request a duplicate.
 *
 * <p>Service persistence code translates its low-level violation (e.g. SQL
 * state 23505 / DataIntegrityViolationException on the
 * {@code UNIQUE(saga_id, idempotency_key, entry_type)} constraint) into this
 * exception. {@link IdempotencyGuard} catches it and switches to the recovery
 * path: read the ORIGINAL result from the database and replay it.
 *
 * <p>This is internal control flow between the persistence layer and the guard,
 * not a client-facing error — deliberately NOT part of lynx-common's sealed
 * LynxException hierarchy. If recovery finds nothing (original still in
 * flight), the guard converts it to the client-facing 409
 * {@code ConflictException.duplicateRequest}.
 */
public final class DuplicateRequestException extends RuntimeException {

  public DuplicateRequestException(String message) {
    super(message);
  }

  public DuplicateRequestException(String message, Throwable cause) {
    super(message, cause);
  }
}
