package com.lynx.account.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynx.account.repository.ProcessedEventRepository;
import com.lynx.events.DomainEvent;
import com.lynx.events.EventCodec;
import com.lynx.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * The consuming side of ADR-002's CDC pipeline — the first real one.
 * Debezium's Postgres connector, registered against
 * {@code ledger-service}'s {@code public.outbox} table (see
 * {@code infra/debezium/ledger-outbox-connector.json}), produces one flat
 * JSON row per outbox insert onto {@code lynx.public.outbox} — the
 * connector's {@code unwrap} SMT ({@code ExtractNewRecordState}) strips
 * Debezium's {@code {before, after, source, op, ts_ms}} change-event
 * envelope entirely, so the Kafka message VALUE here is the outbox row's
 * own columns ({@code id, saga_id, user_id, event_type, payload,
 * created_at}) — either directly, OR wrapped one level inside a Kafka
 * Connect {@code {schema, payload}} JSON-converter envelope. The latter is
 * REQUIRED (see {@code value.converter.schemas.enable: true} in
 * {@code infra/debezium/ledger-outbox-connector.json}) — without it,
 * {@code JsonConverter} silently corrupts this table's OWN {@code payload}
 * column (the JSONB event envelope, a Debezium {@code io.debezium.data.Json}
 * semantic-typed string field) into a bare {@code "{}"}, discovered the hard
 * way running this against a real Debezium instance. {@link #rowNode}
 * disambiguates the two shapes by checking whether the OUTER {@code
 * payload} field is itself an object (the Connect envelope) or a string
 * (this table's own column, unwrapped already) — so both real Debezium
 * messages AND this class's own flat-row unit test fixtures decode
 * identically.
 *
 * <p><b>A short-circuit check, BEFORE decoding {@code payload}</b>
 *: the row's own
 * {@code id} field — which IS the event's id — is read straight off the
 * envelope, so "have I already fully handled this event" (applied
 * successfully, OR already sent to the dead-letter topic) can be checked
 * even when {@code payload} itself later turns out malformed. Without
 * this, a redelivery of a message that was already dead-lettered would
 * get dead-lettered a SECOND time.
 *
 * <p><b>Failures are NOT caught here</b> — deliberately, a change from
 * this class's original version. {@code readTree}, {@code EventCodec.decode},
 * and {@link EventProjector#apply} are all allowed to throw straight out
 * of this method. That hands control to the container's own error
 * handler (a {@code DefaultErrorHandler} + {@code DeadLetterPublishingRecoverer},
 * see {@code BeansConfig}), which retries a bounded number of times (a
 * transient failure — e.g. Postgres briefly unreachable — then succeeds
 * on redelivery) and, only once retries are exhausted, publishes the RAW
 * original message to a {@code .DLT} topic and commits the offset for it
 * — "cannot miss a message" means a permanently
 * malformed or failing message is preserved for investigation, never
 * silently dropped, which the previous catch-log-and-ack version did.
 *
 * <p>Deliberately NOT unit-tested via a real embedded/Testcontainers Kafka
 * broker — this class's OWN
 * logic (the short-circuit, and that failures propagate rather than being
 * swallowed) IS unit-tested directly (mocked collaborators); the
 * container's retry/backoff/dead-letter behavior is Spring Kafka's own
 * library code, not re-proven here.
 */
@Component
public class OutboxEventConsumer {

  private static final Logger log = LoggerFactory.getLogger(OutboxEventConsumer.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final EventProjector eventProjector;
  private final ProcessedEventRepository processedEventRepository;

  public OutboxEventConsumer(EventProjector eventProjector, ProcessedEventRepository processedEventRepository) {
    this.eventProjector = eventProjector;
    this.processedEventRepository = processedEventRepository;
  }

  /** {@code OutboxEntry}'s own placeholder — never a genuine, fully-written event. */
  private static final String PLACEHOLDER_PAYLOAD = "{}";

  @KafkaListener(topics = "${lynx.kafka.outbox-topic}", groupId = "${spring.kafka.consumer.group-id}")
  public void onMessage(String message, Acknowledgment ack) {
    JsonNode root = rowNode(parse(message));
    if (root == null || root.isNull() || root.get("payload") == null) {
      // A DELETE/tombstone, or some other shape without a payload — outbox
      // rows are never deleted or updated by ledger-service, so this
      // shouldn't occur in practice; a genuine no-op, not a failure —
      // acked directly, never sent through the error handler.
      log.debug("Skipping a change event with no 'payload' field");
      ack.acknowledge();
      return;
    }
    if (PLACEHOLDER_PAYLOAD.equals(root.get("payload").asText())) {
      // An intermediate row VERSION, not a genuine event — CDC (Debezium
      // reading Postgres's WAL) replicates every row version a transaction
      // produces, not just its final one. Even though ledger-service now
      // writes the outbox row in a single INSERT already carrying the real
      // payload (see OutboxEntry's own javadoc), a real Debezium instance
      // was STILL observed emitting an extra change event whose payload is
      // this exact placeholder for the SAME row/id. Treating it as a
      // decode failure (and dead-lettering it) would poison this eventId's
      // dedup entry BEFORE the real, later delivery ever arrives — this
      // skip is what actually prevents that: a benign no-op, acked
      // directly, never sent through the error handler.
      log.debug("Skipping a change event whose payload is still the unwritten placeholder");
      ack.acknowledge();
      return;
    }

    long eventId = root.get("id").asLong();
    if (processedEventRepository.existsById(eventId)) {
      // Already fully handled — either successfully applied, or already
      // dead-lettered by a previous delivery of this exact record. Checked
      // FIRST, using the row's own id field, specifically so a malformed
      // payload doesn't get re-decoded (and re-dead-lettered) on redelivery.
      log.debug("Skipping already-handled eventId={}", eventId);
      ack.acknowledge();
      return;
    }

    // Everything below is allowed to throw — see class javadoc.
    EventEnvelope<? extends DomainEvent> envelope = EventCodec.decode(root.get("payload").asText());
    eventProjector.apply(envelope);
    ack.acknowledge();
  }

  private static JsonNode parse(String message) {
    try {
      return MAPPER.readTree(message);
    } catch (Exception e) {
      // A malformed OUTER envelope (not even valid JSON) — genuinely
      // unrecoverable, no eventId can even be extracted from it. Propagate;
      // the container's error handler dead-letters the raw message as-is.
      throw new IllegalStateException("Malformed Debezium message: " + message, e);
    }
  }

  /**
   * Strips the Kafka Connect {@code {schema, payload}} envelope, if present
   * — see this class's own javadoc for why {@code schemas.enable: true} is
   * required and what it does to the wire shape. Disambiguates from this
   * table's OWN {@code payload} column (always a JSON string) by checking
   * whether the outer {@code payload} field is an OBJECT — only the Connect
   * envelope's is; a flat row's own {@code payload} column value never is.
   */
  private static JsonNode rowNode(JsonNode parsed) {
    if (parsed != null && parsed.has("schema") && parsed.has("payload") && parsed.get("payload").isObject()) {
      return parsed.get("payload");
    }
    return parsed;
  }

  /**
   * Best-effort — used by {@link ProcessedEventMarkingRecoverer} to extract
   * the same {@code id} field this class's own short-circuit checks, from
   * a record that's already known to be going to the dead-letter topic.
   * Returns {@code null} rather than throwing if the value can't even be
   * parsed as JSON, or has no {@code id} field — the DLT publish itself
   * (the recoverer's real job) still happens either way; this is purely
   * "also remember this id, if we can" on top of that.
   */
  static Long extractEventIdOrNull(String message) {
    try {
      JsonNode root = rowNode(MAPPER.readTree(message));
      JsonNode id = root == null ? null : root.get("id");
      return id == null || id.isNull() ? null : id.asLong();
    } catch (Exception e) {
      return null;
    }
  }
}
