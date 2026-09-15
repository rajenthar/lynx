package com.lynx.account.projection;

import com.lynx.account.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/**
 * Wraps a delegate recoverer (a {@code DeadLetterPublishingRecoverer} —
 * see {@code BeansConfig}) to ALSO record the failed event's id in {@code
 * processed_events}, right after the delegate has actually published it
 * to the dead-letter topic (other-docs/12 Decision 8, raised directly on
 * review: "even for error messages we need to store the processedEvent" —
 * without this, a redelivery of the exact same record — a rebalance, a
 * retry racing the DLT publish, etc. — would get sent to the DLT a SECOND
 * time; {@link OutboxEventConsumer}'s own {@code existsById} short-circuit
 * is what actually prevents that, and it needs this row to exist for a
 * dead-lettered event exactly the same way it needs one for a
 * successfully-applied event).
 *
 * <p>Deliberately best-effort for the id extraction ({@link
 * OutboxEventConsumer#extractEventIdOrNull}) — the delegate's own dead-letter
 * publish is the actual "don't lose this message" guarantee; this is only
 * "also avoid a duplicate DLT entry on top of that," so a record whose id
 * can't even be parsed just skips the marking step rather than failing
 * the whole recovery.
 */
public class ProcessedEventMarkingRecoverer implements ConsumerRecordRecoverer {

  private static final Logger log = LoggerFactory.getLogger(ProcessedEventMarkingRecoverer.class);

  private final ConsumerRecordRecoverer delegate;
  private final ProcessedEventRepository processedEventRepository;

  public ProcessedEventMarkingRecoverer(ConsumerRecordRecoverer delegate,
                                         ProcessedEventRepository processedEventRepository) {
    this.delegate = delegate;
    this.processedEventRepository = processedEventRepository;
  }

  @Override
  public void accept(ConsumerRecord<?, ?> record, Exception exception) {
    delegate.accept(record, exception);

    Object value = record.value();
    Long eventId = value instanceof String s ? OutboxEventConsumer.extractEventIdOrNull(s) : null;
    if (eventId == null) {
      log.warn("Dead-lettered a record but could not extract its eventId — it may be "
          + "re-processed (and re-dead-lettered) on redelivery: topic={}, partition={}, offset={}",
          record.topic(), record.partition(), record.offset());
      return;
    }
    processedEventRepository.markProcessedIfNew(eventId);
    log.warn("Dead-lettered eventId={} after exhausting retries (topic={}, partition={}, offset={}): {}",
        eventId, record.topic(), record.partition(), record.offset(), exception.getMessage());
  }
}
