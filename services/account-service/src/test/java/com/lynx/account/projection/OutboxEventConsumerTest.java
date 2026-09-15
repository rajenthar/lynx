package com.lynx.account.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lynx.account.repository.ProcessedEventRepository;
import com.lynx.events.EventEnvelope;
import com.lynx.events.FundsDeposited;
import com.lynx.events.MoneyAmount;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Real Postgres/Kafka are both out of scope here (other-docs/12's
 * test-boundary decision) — this proves only what {@link
 * OutboxEventConsumer#onMessage} itself decides: skip vs. process vs.
 * propagate. The container's own retry/backoff/dead-letter behavior,
 * triggered by a propagated exception, is Spring Kafka's library code.
 */
class OutboxEventConsumerTest {

  private EventProjector eventProjector;
  private ProcessedEventRepository processedEventRepository;
  private Acknowledgment ack;
  private OutboxEventConsumer consumer;

  @BeforeEach
  void setUp() {
    eventProjector = mock(EventProjector.class);
    processedEventRepository = mock(ProcessedEventRepository.class);
    ack = mock(Acknowledgment.class);
    consumer = new OutboxEventConsumer(eventProjector, processedEventRepository);
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The unwrapped (post-SMT) shape — see {@link OutboxEventConsumer}'s own
   * javadoc. Built with Jackson itself, not manual string concatenation —
   * {@code payload} must be a genuine JSON STRING field (Debezium's
   * default mapping for a Postgres {@code JSON}/{@code JSONB} column is a
   * string carrying the JSON text verbatim, not a nested object), and
   * {@code ObjectNode.put(String, String)} is what correctly produces that.
   */
  private static String debeziumMessage(long id, String sagaId, String payloadJson) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("id", id);
    node.put("saga_id", sagaId);
    node.put("user_id", "user-1");
    node.put("event_type", "FUNDS_DEPOSITED");
    node.put("payload", payloadJson);
    node.put("created_at", "2026-01-01T00:00:00Z");
    return node.toString();
  }

  @Test
  void skipsAndAcksWhenThereIsNoPayloadField() {
    consumer.onMessage("{\"id\":1,\"before\":null,\"after\":null,\"op\":\"d\"}", ack);

    verify(ack).acknowledge();
    verify(eventProjector, never()).apply(any());
  }

  @Test
  void skipsAndAcksWithoutDecodingWhenTheEventIdWasAlreadyHandled() {
    when(processedEventRepository.existsById(42L)).thenReturn(true);
    // Deliberately malformed payload — proves the existsById short-circuit
    // happens BEFORE decoding, exactly the "very beginning" check raised
    // on review (other-docs/12 Decision 8): this must NOT throw.
    String message = "{\"id\":42,\"saga_id\":\"" + UUID.randomUUID() + "\",\"payload\":\"not valid json at all\"}";

    consumer.onMessage(message, ack);

    verify(ack).acknowledge();
    verify(eventProjector, never()).apply(any());
  }

  @Test
  void appliesAndAcksOnANewValidEvent() {
    UUID sagaId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(processedEventRepository.existsById(7L)).thenReturn(false);
    EventEnvelope<FundsDeposited> envelope = EventEnvelope.of(
        7L, sagaId, "corr-1", new FundsDeposited(accountId, new MoneyAmount(10000, "SGD")));
    String message = debeziumMessage(7L, sagaId.toString(),
        com.lynx.events.EventCodec.encode(envelope));

    consumer.onMessage(message, ack);

    verify(eventProjector).apply(any());
    verify(ack).acknowledge();
  }

  @Test
  void propagatesWithoutAckingWhenTheOuterMessageIsNotValidJson() {
    assertThatThrownBy(() -> consumer.onMessage("not json at all", ack))
        .isInstanceOf(RuntimeException.class);

    verify(ack, never()).acknowledge();
  }

  @Test
  void propagatesWithoutAckingWhenThePayloadFailsToDecode() {
    when(processedEventRepository.existsById(9L)).thenReturn(false);
    String message = "{\"id\":9,\"saga_id\":\"" + UUID.randomUUID() + "\",\"payload\":\"{}\"}";

    assertThatThrownBy(() -> consumer.onMessage(message, ack))
        .isInstanceOf(RuntimeException.class);

    verify(ack, never()).acknowledge();
    verify(eventProjector, never()).apply(any());
  }

  @Test
  void propagatesWithoutAckingWhenEventProjectorApplyThrows() {
    UUID sagaId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    when(processedEventRepository.existsById(11L)).thenReturn(false);
    EventEnvelope<FundsDeposited> envelope = EventEnvelope.of(
        11L, sagaId, "corr-1", new FundsDeposited(accountId, new MoneyAmount(10000, "SGD")));
    String message = debeziumMessage(11L, sagaId.toString(),
        com.lynx.events.EventCodec.encode(envelope));
    org.mockito.Mockito.doThrow(new RuntimeException("simulated transient DB failure"))
        .when(eventProjector).apply(any());

    assertThatThrownBy(() -> consumer.onMessage(message, ack))
        .isInstanceOf(RuntimeException.class);

    verify(ack, never()).acknowledge();
  }

  @Test
  void extractEventIdOrNullReturnsTheIdField() {
    assertThat(OutboxEventConsumer.extractEventIdOrNull("{\"id\":5,\"payload\":\"{}\"}")).isEqualTo(5L);
  }

  @Test
  void extractEventIdOrNullReturnsNullForUnparsableInput() {
    assertThat(OutboxEventConsumer.extractEventIdOrNull("not json")).isNull();
    assertThat(OutboxEventConsumer.extractEventIdOrNull("{\"no_id_field\":true}")).isNull();
  }
}
